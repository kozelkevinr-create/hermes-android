package com.hermes.client.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePreviewTest {
    @Test fun short_text_is_unchanged() {
        assertEquals("hello there", previewText("hello there"))
    }

    @Test fun newlines_and_runs_of_whitespace_collapse_to_one_line() {
        assertEquals("line one line two", previewText("line one\n\n  line\ttwo\r\n"))
    }

    @Test fun blank_body_becomes_empty() {
        assertEquals("", previewText("  \n\t "))
    }

    @Test fun long_text_is_capped_with_an_ellipsis_within_the_limit() {
        val out = previewText("a".repeat(500))
        assertEquals(MAX_PREVIEW_LENGTH, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun text_exactly_at_the_limit_is_kept_whole() {
        val exact = "b".repeat(MAX_PREVIEW_LENGTH)
        assertEquals(exact, previewText(exact))
    }

    @Test fun custom_cap_is_respected_and_trailing_space_before_the_ellipsis_is_dropped() {
        assertEquals("one…", previewText("one two three", max = 5))
    }

    @Test fun sender_label_prefers_own_then_display_name_then_localpart() {
        assertEquals("You", senderLabel("@me:example.test", "Me", isOwn = true))
        assertEquals("Hermes Bot", senderLabel("@bot:example.test", "  Hermes Bot ", isOwn = false))
        assertEquals("bot", senderLabel("@bot:example.test", null, isOwn = false))
        assertEquals("bot", senderLabel("@bot:example.test", "   ", isOwn = false))
    }

    @Test fun avatar_letter_is_the_uppercased_first_character() {
        assertEquals("H", avatarLetterFor("hermes"))
        assertEquals("É", avatarLetterFor("  élan"))
        assertEquals("#", avatarLetterFor("   "))
    }
}
