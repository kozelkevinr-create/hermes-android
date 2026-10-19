package com.hermes.client.data.matrix

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A channel of diff batches whose carried native handles must be released exactly once.
 *
 * The SDK calls [send] from its own thread and a single collector consumes [batches]. Each batch's
 * items are owned by exactly one party: the collector once it receives the batch, or [onDiscard]
 * when the batch is dropped (sent after close, or drained on [close]).
 */
internal class ClosableDiffQueue<T>(private val onDiscard: (T) -> Unit) {
    private val channel = Channel<List<T>>(Channel.UNLIMITED)

    /** Guards [send] and [close] together so a send can never slip past the closed check. */
    private val lock = Any()

    private var closed = false

    /** Stream consumed by the collector; a batch is owned only after it is received from here. */
    val batches: Flow<List<T>> = channel.receiveAsFlow()

    /**
     * Queues [batch]. Returns true when ownership transfers to the collector. Returns false — and
     * discards every item — once [closed] is set or when the send fails, so a late callback on a
     * closed handle never leaks its items.
     *
     * Both the closed check and the [Channel.trySend] happen under [lock], so [close] cannot run
     * between them (which would strand a batch that passed the check but was sent after the drain).
     * Discarding a rejected batch happens after the lock is released.
     */
    fun send(batch: List<T>): Boolean {
        val accepted = synchronized(lock) { !closed && channel.trySend(batch).isSuccess }
        // Rejected: discard outside the lock, since onDiscard destroys FFI handles.
        if (!accepted) batch.forEach(onDiscard)
        return accepted
    }

    /** Marks the queue closed (atomically, so double close is a no-op), then drains the buffer. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            channel.close()
        }
        // Buffered items stay receivable after close, so drain them here (outside the lock, since
        // onDiscard may destroy FFI handles and must not hold it). No new item can arrive after
        // close(): send either failed above or was already received into the channel's buffer.
        while (true) {
            val batch = channel.tryReceive().getOrNull() ?: break
            batch.forEach(onDiscard)
        }
    }
}
