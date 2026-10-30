package com.hermes.client.data.ntfy

import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** How many past messages are kept; older ones fall off. */
const val NTFY_HISTORY_LIMIT = 300

/**
 * Folds [incoming] into [existing]: deduped by message id (the incoming copy wins), newest first. Only
 * unpinned messages count toward [limit]; pinned ones are always kept. A message with no timestamp is
 * dated [nowSeconds] so it still sorts sensibly.
 */
fun mergeNtfyHistory(
    existing: List<NtfyMessage>,
    incoming: List<NtfyMessage>,
    nowSeconds: Long,
    pinned: Set<String> = emptySet(),
    limit: Int = NTFY_HISTORY_LIMIT,
): List<NtfyMessage> {
    val dated = incoming.map { if (it.timeSeconds > 0) it else it.copy(timeSeconds = nowSeconds) }
    val ids = dated.map { it.id }.toSet()
    val (keep, rest) = (dated + existing.filterNot { it.id in ids })
        .sortedByDescending { it.timeSeconds }
        .partition { it.id in pinned }
    return (keep + rest.take(limit)).sortedByDescending { it.timeSeconds }
}

/** Messages that arrived after [lastSeenSeconds] and are not pinned (pinned ones are being kept, not waiting). */
fun unreadNtfyCount(messages: List<NtfyMessage>, pinned: Set<String>, lastSeenSeconds: Long): Int =
    messages.count { it.timeSeconds > lastSeenSeconds && it.id !in pinned }

/**
 * Every ntfy message the app has received, kept so one that popped up and was swiped away can still be
 * read. Fed live by the connection service and, on request, from what the ntfy server still caches.
 */
@Singleton
class NtfyHistory @Inject constructor(
    private val store: NtfyStore,
    private val listener: NtfyListener,
) {
    val messages = store.history
    val pinned = store.pinned

    /** How many messages arrived since the history was last opened. */
    val unread = kotlinx.coroutines.flow.combine(store.history, store.pinned, store.lastSeen, ::unreadNtfyCount)

    /** Marks everything received so far as seen. */
    suspend fun markSeen() {
        val newest = store.history.first().maxOfOrNull { it.timeSeconds } ?: 0L
        store.markSeen(maxOf(System.currentTimeMillis() / 1000, newest))
    }

    suspend fun record(message: NtfyMessage) {
        store.updateHistory { h, pins -> mergeNtfyHistory(h, listOf(message), System.currentTimeMillis() / 1000, pins) }
    }

    /** Pulls the server's cached messages into the history. Returns how many were read, or the error. */
    suspend fun refreshFromServer(): Result<Int> =
        listener.fetchCached().onSuccess { fetched ->
            if (fetched.isNotEmpty()) {
                store.updateHistory { h, pins -> mergeNtfyHistory(h, fetched, System.currentTimeMillis() / 1000, pins) }
            }
        }.map { it.size }

    suspend fun setPinned(id: String, pinned: Boolean) = store.setPinned(id, pinned)

    /** Removes one message for good; a later server refresh does not bring it back. */
    suspend fun dismiss(id: String) = store.dismiss(listOf(id))

    /** Removes every unpinned message for good. */
    suspend fun clear(ids: Collection<String>) = store.dismiss(ids)
}
