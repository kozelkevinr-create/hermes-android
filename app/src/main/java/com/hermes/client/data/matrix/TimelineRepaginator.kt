package com.hermes.client.data.matrix

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced repagination for timeline `Clear` / empty-`Reset` batches. The SDK can emit these a few
 * seconds after a room opens; an empty list is not the final state, so the consumer must page
 * backwards again — but never in a tight loop: a reset schedules at most one page, only after the
 * first page has been requested, and stops for good once [page] reports the start of the room.
 */
class TimelineRepaginator(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val page: suspend (count: Int) -> Boolean,
) {
    private var loaded = false
    private var atStart = false
    private var pending: Job? = null

    /** Record that the first page request has happened; resets before it are ignored. */
    fun onFirstPageRequested() {
        loaded = true
    }

    /** Feed one diff batch; [wasReset] is true when it contains `Clear` or an empty `Reset`. */
    fun onBatch(wasReset: Boolean) {
        if (!loaded || atStart || !wasReset) return
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            if (page(DEFAULT_PAGE_SIZE)) atStart = true
        }
    }

    /** Cancel any pending page; the owning handle is closing. */
    fun close() {
        pending?.cancel()
        pending = null
        loaded = false
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val DEFAULT_DEBOUNCE_MS = 250L
    }
}
