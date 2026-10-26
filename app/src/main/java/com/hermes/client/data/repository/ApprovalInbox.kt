package com.hermes.client.data.repository

import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.ntfy.NtfyApproval
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.str
import com.hermes.client.ui.chat.ApprovalChoice
import com.hermes.client.ui.chat.ApprovalRequest
import com.hermes.client.ui.chat.approvalRequestFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An approval the agent is waiting on. [rpcId] is the gateway's server-request id (matches `request.cancel`). */
data class PendingApproval(
    val rpcId: String,
    val sessionId: String,
    val request: ApprovalRequest,
    val receivedAtMillis: Long,
    /** Set when the approval came from ntfy: it is answered through the relay, not the gateway socket. */
    val ntfy: NtfyApproval? = null,
)

/** The id a ntfy approval carries in [PendingApproval.rpcId], so one id space covers both sources. */
fun ntfyApprovalKey(messageId: String) = "ntfy:$messageId"

/** The notification id an approval was posted under, from either source. */
fun notificationIdFor(item: PendingApproval): Int =
    item.ntfy?.let { com.hermes.client.notifications.ntfyNotificationId(it.messageId) }
        ?: com.hermes.client.notifications.notificationId(
            com.hermes.client.notifications.Notif.EVENT_APPROVAL,
            com.hermes.client.notifications.approvalKey(item.sessionId, item.rpcId),
        )

/** Adds [a] (deduped by message id and request id) as a pending approval for the inbox. */
fun List<PendingApproval>.withNtfyApproval(a: NtfyApproval, nowMillis: Long): List<PendingApproval> {
    val key = ntfyApprovalKey(a.messageId)
    if (any { it.rpcId == key || it.request.requestId == a.requestId }) return this
    val req = ApprovalRequest(
        command = a.command,
        description = a.description,
        patternKeys = emptyList(),
        allowPermanent = ApprovalChoice.ALWAYS in a.allowed,
        // Without "session" the host treats this as a one-shot override (a reviewer-denied command).
        smartDenied = ApprovalChoice.SESSION !in a.allowed,
        requestId = a.requestId,
        rpcId = key,
    )
    return this + PendingApproval(key, "ntfy", req, nowMillis, a)
}

/**
 * Folds an event into the open-approvals list: `approval.request` adds (deduped by request id, then
 * rpc id), `request.cancel` for an approval removes by rpc id. Pure so it is unit-testable.
 */
fun List<PendingApproval>.applyApprovalEvent(event: ServerEvent, nowMillis: Long): List<PendingApproval> =
    when (event.type) {
        "approval.request" -> {
            val sid = event.sessionId
            val req = approvalRequestFrom(event)
            val rpcId = event.str("rpc_id").orEmpty()
            if (sid == null || (rpcId.isEmpty() && req.requestId.isEmpty())) this
            else if (any { (rpcId.isNotEmpty() && it.rpcId == rpcId) ||
                    (req.requestId.isNotEmpty() && it.request.requestId == req.requestId) }) this
            else this + PendingApproval(rpcId, sid, req, nowMillis)
        }
        "request.cancel" ->
            if (event.str("method") == "approval") filterNot { it.rpcId == event.str("id") } else this
        else -> this
    }

/**
 * App-wide list of approvals awaiting a decision, so they can be reviewed from one screen even if the
 * chat they belong to was never opened. Fed by the gateway event stream for the life of the process.
 */
@javax.inject.Singleton
class ApprovalInbox @javax.inject.Inject constructor(
    private val chat: ChatRepository,
    okHttp: okhttp3.OkHttpClient,
    private val notifier: com.hermes.client.notifications.HermesNotifier,
) {
    // Reply links go to the relay on the LAN; no gateway cookies or re-login belong on that call.
    private val http = okHttp.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .authenticator(okhttp3.Authenticator.NONE)
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    init {
        scope.launch {
            chat.events.collect { event ->
                runCatching { _pending.value = _pending.value.applyApprovalEvent(event, System.currentTimeMillis()) }
            }
        }
    }

    /** Shows an approval that arrived over ntfy. */
    fun addNtfy(a: NtfyApproval) {
        _pending.value = _pending.value.withNtfyApproval(a, System.currentTimeMillis())
    }

    /** The publisher withdrew ntfy message [messageId] (answered elsewhere or timed out). */
    fun removeNtfy(messageId: String) {
        val key = ntfyApprovalKey(messageId)
        _pending.value = _pending.value.filterNot { it.rpcId == key }
    }

    /** Sends the decision; the card leaves the list only once it was accepted. */
    suspend fun respond(item: PendingApproval, choice: ApprovalChoice): Result<Unit> =
        runCatching {
            val ntfy = item.ntfy
            if (ntfy != null) replyViaRelay(ntfy, choice)
            else chat.respondApproval(item.sessionId, choice, item.request.requestId)
        }
            // Answered here, so drop its notification now rather than waiting for the gateway to withdraw it.
            .onSuccess { notifier.cancel(notificationIdFor(item)) }
            .onSuccess { _pending.value = _pending.value.filterNot { it.rpcId == item.rpcId && it.request.requestId == item.request.requestId } }
            .onFailure { DebugLog.log("approvals", "respond failed: ${it.message}") }

    private suspend fun replyViaRelay(a: NtfyApproval, choice: ApprovalChoice) {
        val url = a.replyUrl(choice) ?: error("${choice.wire} is not allowed for this approval")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("relay answered ${resp.code}")
            }
        }
    }
}
