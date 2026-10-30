package com.hermes.client.data.ntfy

import org.junit.Assert.assertEquals
import org.junit.Test

class NtfyHistoryTest {
    private fun msg(id: String, time: Long, text: String = id) =
        NtfyMessage(id, "t", time, "", text, 3, emptyList(), emptyList())

    @Test fun newest_first_and_deduped_by_id_with_the_incoming_copy_winning() {
        val merged = mergeNtfyHistory(listOf(msg("a", 10, "old"), msg("b", 20)), listOf(msg("a", 10, "new"), msg("c", 30)), nowSeconds = 99)
        assertEquals(listOf("c", "b", "a"), merged.map { it.id })
        assertEquals("new", merged.last().message)
    }

    @Test fun message_without_a_time_is_dated_now() {
        val merged = mergeNtfyHistory(listOf(msg("a", 10)), listOf(msg("z", 0)), nowSeconds = 50)
        assertEquals(listOf("z", "a"), merged.map { it.id })
        assertEquals(50L, merged.first().timeSeconds)
    }

    @Test fun capped_to_the_limit_dropping_the_oldest() {
        val merged = mergeNtfyHistory((1L..5L).map { msg("m$it", it) }, emptyList(), nowSeconds = 99, limit = 3)
        assertEquals(listOf("m5", "m4", "m3"), merged.map { it.id })
    }

    @Test fun pinned_messages_survive_the_cap() {
        val merged = mergeNtfyHistory((1L..5L).map { msg("m$it", it) }, emptyList(), nowSeconds = 99, pinned = setOf("m1"), limit = 2)
        assertEquals(listOf("m5", "m4", "m1"), merged.map { it.id })
    }
}
