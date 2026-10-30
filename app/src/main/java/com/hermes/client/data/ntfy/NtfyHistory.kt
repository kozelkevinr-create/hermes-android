package com.hermes.client.data.ntfy

import javax.inject.Inject
import javax.inject.Singleton

/** How many past messages are kept; older ones fall off. */
const val NTFY_HISTORY_LIMIT = 300

/**
 * Folds [incoming] into [existing]: deduped by message id (the incoming copy wins), newest first,
 * capped at [limit]. A message with no timestamp is dated [nowSeconds] so it still sorts sensibly.
 */
fun mergeNtfyHistory(
    existing: List<NtfyMessage>,
    incoming: List<NtfyMessage>,
    nowSeconds: Long,
    limit: Int = NTFY_HISTORY_LIMIT,
): List<NtfyMessage> {
    val dated = incoming.map { if (it.timeSeconds > 0) it else it.copy(timeSeconds = nowSeconds) }
    val ids = dated.map { it.id }.toSet()
    return (dated + existing.filterNot { it.id in ids })
        .sortedByDescending { it.timeSeconds }
        .take(limit)
}

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

    suspend fun record(message: NtfyMessage) {
        store.updateHistory { mergeNtfyHistory(it, listOf(message), System.currentTimeMillis() / 1000) }
    }

    /** Pulls the server's cached messages into the history. Returns how many were read, or the error. */
    suspend fun refreshFromServer(): Result<Int> =
        listener.fetchCached().onSuccess { fetched ->
            if (fetched.isNotEmpty()) {
                store.updateHistory { mergeNtfyHistory(it, fetched, System.currentTimeMillis() / 1000) }
            }
        }.map { it.size }

    suspend fun clear() = store.updateHistory { emptyList() }
}
