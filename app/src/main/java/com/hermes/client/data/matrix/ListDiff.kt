package com.hermes.client.data.matrix

/**
 * SDK-independent mirror of the matrix-rust-sdk list diffs (`TimelineDiff`, `RoomListEntriesUpdate`).
 * Both are the same eyeball-im vector diff vocabulary, so one pure applier serves the room list
 * and the timeline. The debug/SDK layer maps its types onto these.
 */
sealed interface ListDiff<out T> {
    data class Append<T>(val values: List<T>) : ListDiff<T>
    data object Clear : ListDiff<Nothing>
    data class PushFront<T>(val value: T) : ListDiff<T>
    data class PushBack<T>(val value: T) : ListDiff<T>
    data object PopFront : ListDiff<Nothing>
    data object PopBack : ListDiff<Nothing>
    data class Insert<T>(val index: Int, val value: T) : ListDiff<T>
    data class Set<T>(val index: Int, val value: T) : ListDiff<T>
    data class Remove(val index: Int) : ListDiff<Nothing>
    data class Truncate(val length: Int) : ListDiff<Nothing>
    data class Reset<T>(val values: List<T>) : ListDiff<T>
}

/** Short variant + size label for logging, never the content. */
fun ListDiff<*>.label(): String = when (this) {
    is ListDiff.Append -> "Append(${values.size})"
    is ListDiff.Clear -> "Clear"
    is ListDiff.PushFront -> "PushFront"
    is ListDiff.PushBack -> "PushBack"
    is ListDiff.PopFront -> "PopFront"
    is ListDiff.PopBack -> "PopBack"
    is ListDiff.Insert -> "Insert($index)"
    is ListDiff.Set -> "Set($index)"
    is ListDiff.Remove -> "Remove($index)"
    is ListDiff.Truncate -> "Truncate($length)"
    is ListDiff.Reset -> "Reset(${values.size})"
}

/**
 * Applies [diffs] in order to a copy of [list] and returns the result. Out-of-range indices are
 * clamped / ignored rather than thrown, so a stale or reordered batch can't kill a listener.
 */
fun <T> applyDiffs(list: List<T>, diffs: List<ListDiff<T>>): List<T> {
    val out = list.toMutableList()
    for (d in diffs) {
        when (d) {
            is ListDiff.Append -> out.addAll(d.values)
            is ListDiff.Clear -> out.clear()
            is ListDiff.PushFront -> out.add(0, d.value)
            is ListDiff.PushBack -> out.add(d.value)
            is ListDiff.PopFront -> if (out.isNotEmpty()) out.removeAt(0)
            is ListDiff.PopBack -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            is ListDiff.Insert -> out.add(d.index.coerceIn(0, out.size), d.value)
            is ListDiff.Set -> if (d.index in out.indices) out[d.index] = d.value
            is ListDiff.Remove -> if (d.index in out.indices) out.removeAt(d.index)
            is ListDiff.Truncate -> while (out.size > d.length.coerceAtLeast(0)) out.removeAt(out.lastIndex)
            is ListDiff.Reset -> {
                out.clear()
                out.addAll(d.values)
            }
        }
    }
    return out
}
