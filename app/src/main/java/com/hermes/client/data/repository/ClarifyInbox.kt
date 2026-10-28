package com.hermes.client.data.repository

import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.network.ClarifyQuestion
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.clarifyQuestions
import com.hermes.client.data.network.str
import com.hermes.client.notifications.Notif
import com.hermes.client.notifications.HermesNotifier
import com.hermes.client.notifications.notificationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A question the agent is waiting on. [requestId] is the gateway's server-request id (matches `request.cancel`). */
data class PendingClarify(
    val requestId: String,
    val sessionId: String,
    val questions: List<ClarifyQuestion>,
    val receivedAtMillis: Long,
) {
    /** The first question, for list cards. */
    val question: String get() = questions.firstOrNull()?.question.orEmpty()
}

/**
 * Folds an event into the open-clarifications list: `clarify.request` adds (deduped by request id),
 * `request.cancel` for a clarify removes by request id. Pure so it is unit-testable.
 */
fun List<PendingClarify>.applyClarifyEvent(event: ServerEvent, nowMillis: Long): List<PendingClarify> =
    when (event.type) {
        "clarify.request" -> {
            val sid = event.sessionId
            val id = event.str("request_id").orEmpty()
            if (sid == null || id.isEmpty() || any { it.requestId == id }) this
            else this + PendingClarify(id, sid, event.clarifyQuestions(), nowMillis)
        }
        "request.cancel" ->
            if (event.str("method") == "clarify") filterNot { it.requestId == event.str("id") } else this
        else -> this
    }

/**
 * App-wide list of agent questions awaiting an answer, so one dismissed or missed in chat can still be
 * found and answered from a screen. Fed by the gateway event stream for the life of the process.
 */
@javax.inject.Singleton
class ClarifyInbox @javax.inject.Inject constructor(
    private val chat: ChatRepository,
    private val notifier: HermesNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _pending = MutableStateFlow<List<PendingClarify>>(emptyList())
    val pending: StateFlow<List<PendingClarify>> = _pending.asStateFlow()

    init {
        scope.launch {
            chat.events.collect { event ->
                runCatching { _pending.value = _pending.value.applyClarifyEvent(event, System.currentTimeMillis()) }
            }
        }
        // Answered from the chat dialog or a notification reply: it is no longer open.
        scope.launch {
            chat.clarifyAnswered.collect { id -> _pending.value = _pending.value.filterNot { it.requestId == id } }
        }
    }

    /** Sends [answer]; the card leaves the list once the gateway accepted it. */
    suspend fun respond(item: PendingClarify, answers: Map<String, kotlinx.serialization.json.JsonElement>): Result<Unit> =
        runCatching { chat.respondClarifyAnswers(item.sessionId, item.requestId, answers) }
            .onSuccess { notifier.cancel(notificationId(Notif.EVENT_CLARIFY, item.sessionId)) }
            .onFailure { DebugLog.log("clarify", "respond failed: ${it.message}") }
}
