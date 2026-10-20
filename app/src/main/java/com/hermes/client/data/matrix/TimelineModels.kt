package com.hermes.client.data.matrix

/**
 * SDK-independent timeline model. The debug/SDK layer maps the FFI `TimelineItem` types onto these;
 * everything downstream (presentation, ViewModel) works on this pure model so it is JVM-testable.
 */
sealed interface TimelineEntry {
    /** A virtual date divider between two days. */
    data class DayDivider(val ts: Long) : TimelineEntry

    /** The read marker; carries no content, the UI may ignore it. */
    data object ReadMarker : TimelineEntry

    data class Message(
        val id: String,
        val sender: String,
        val senderName: String,
        val isOwn: Boolean,
        val ts: Long,
        val body: String,
        val isEdited: Boolean,
        val kind: MessageKind,
        val sendState: MessageSendState,
        /** True for own text messages the user may edit with a new markdown body. */
        val isEditable: Boolean = false,
        /** SDK identity used to retry or abort this event's local echo, when known. */
        val itemId: EventOrTxnId? = null,
    ) : TimelineEntry

    /** An event we could not decrypt, shown as "Encrypted message". */
    data class Utd(val id: String, val sender: String, val ts: Long) : TimelineEntry

    /** A membership / name / topic change, already reduced to a short human summary. */
    data class StateEvent(val id: String, val ts: Long, val summary: String) : TimelineEntry

    /** An item the client does not render (unknown virtual/event type, or a failed mapping). */
    data class Unsupported(val id: String) : TimelineEntry
}

/** Message kinds the timeline renders differently (text, image/file placeholders, etc.). */
enum class MessageKind { TEXT, NOTICE, EMOTE, IMAGE, FILE, OTHER }

/** Opaque SDK identity of a timeline event: either a remote event id or a local transaction id. */
sealed interface EventOrTxnId {
    data class Event(val eventId: String) : EventOrTxnId
    data class Transaction(val transactionId: String) : EventOrTxnId
}

/** Local send state of a message, mirroring the SDK's `EventSendState`. */
enum class MessageSendState { NOT_SENT_YET, SENDING_FAILED, SENT }

/** Back-pagination state surfaced to the UI. */
enum class PaginationState { Idle, Loading, AtStart }
