package com.hermes.client.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Test

class ListDiffTest {
    private fun apply(start: List<String>, vararg d: ListDiff<String>) = applyDiffs(start, d.toList())

    @Test fun append_adds_in_order() = assertEquals(listOf("a", "b", "c"), apply(listOf("a"), ListDiff.Append(listOf("b", "c"))))

    @Test fun clear_empties() = assertEquals(emptyList<String>(), apply(listOf("a", "b"), ListDiff.Clear))

    @Test fun push_front_and_back() =
        assertEquals(listOf("f", "a", "b"), apply(listOf("a"), ListDiff.PushFront("f"), ListDiff.PushBack("b")))

    @Test fun pop_front_and_back() =
        assertEquals(listOf("b"), apply(listOf("a", "b", "c"), ListDiff.PopFront, ListDiff.PopBack))

    @Test fun pops_on_empty_are_noops() =
        assertEquals(emptyList<String>(), apply(emptyList(), ListDiff.PopFront, ListDiff.PopBack))

    @Test fun insert_at_index_and_clamped() {
        assertEquals(listOf("a", "x", "b"), apply(listOf("a", "b"), ListDiff.Insert(1, "x")))
        assertEquals(listOf("a", "x"), apply(listOf("a"), ListDiff.Insert(9, "x")))
    }

    @Test fun set_replaces_and_ignores_out_of_range() {
        assertEquals(listOf("a", "z"), apply(listOf("a", "b"), ListDiff.Set(1, "z")))
        assertEquals(listOf("a"), apply(listOf("a"), ListDiff.Set(5, "z")))
    }

    @Test fun remove_by_index_and_ignores_out_of_range() {
        assertEquals(listOf("a", "c"), apply(listOf("a", "b", "c"), ListDiff.Remove(1)))
        assertEquals(listOf("a"), apply(listOf("a"), ListDiff.Remove(3)))
    }

    @Test fun truncate_shortens_but_never_grows() {
        assertEquals(listOf("a"), apply(listOf("a", "b", "c"), ListDiff.Truncate(1)))
        assertEquals(listOf("a"), apply(listOf("a"), ListDiff.Truncate(5)))
    }

    @Test fun reset_replaces_everything() =
        assertEquals(listOf("x", "y"), apply(listOf("a", "b", "c"), ListDiff.Reset(listOf("x", "y"))))

    @Test fun batch_is_applied_in_order_and_input_untouched() {
        val start = listOf("a")
        val out = apply(start, ListDiff.Reset(listOf("1", "2")), ListDiff.PushBack("3"), ListDiff.Remove(0))
        assertEquals(listOf("2", "3"), out)
        assertEquals(listOf("a"), start)
    }

    @Test fun describe_has_variant_and_size_but_no_content() {
        assertEquals("Reset(2)", ListDiff.Reset(listOf("secret", "x")).label())
        assertEquals("Insert(3)", ListDiff.Insert(3, "secret").label())
        assertEquals("PopBack", ListDiff.PopBack.label())
    }
}
