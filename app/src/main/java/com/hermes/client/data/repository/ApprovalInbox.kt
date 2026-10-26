package com.hermes.client.data.repository

import com.hermes.client.data.diagnostics.DebugLog
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
)

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
class ApprovalInbox @javax.inject.Inject constructor(private val chat: ChatRepository) {
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

    /** Sends the decision; the card leaves the list only once the gateway accepted it. */
    suspend fun respond(item: PendingApproval, choice: ApprovalChoice): Result<Unit> =
        runCatching { chat.respondApproval(item.sessionId, choice, item.request.requestId) }
            .onSuccess { _pending.value = _pending.value.filterNot { it.rpcId == item.rpcId && it.request.requestId == item.request.requestId } }
            .onFailure { DebugLog.log("approvals", "respond failed: ${it.message}") }
}
