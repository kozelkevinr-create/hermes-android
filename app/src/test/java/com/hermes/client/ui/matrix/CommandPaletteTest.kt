package com.hermes.client.ui.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandPaletteTest {

    private fun entry(name: String, description: String = "") = CommandEntry(name, description)

    // ---- commandPaletteQuery ----

    @Test
    fun empty_draft_has_no_palette() {
        assertNull(commandPaletteQuery(""))
    }

    @Test
    fun plain_draft_has_no_palette() {
        assertNull(commandPaletteQuery("hello"))
        assertNull(commandPaletteQuery("/model with args"))
    }

    @Test
    fun bare_slash_query_is_empty_not_null() {
        // "/" means "show every command".
        assertEquals("", commandPaletteQuery("/"))
    }

    @Test
    fun slash_word_query_lowercases_the_token() {
        assertEquals("mo", commandPaletteQuery("/mo"))
        assertEquals("model", commandPaletteQuery("/Model"))
    }

    @Test
    fun a_space_hides_the_palette_even_after_slash() {
        assertNull(commandPaletteQuery("/model "))
        assertNull(commandPaletteQuery("/model fast"))
    }

    // ---- filterCommands ----

    private val catalog = listOf(
        entry("/new", "Start a new session"),
        entry("/model", "Switch the model"),
        entry("/stop", "Interrupt the agent"),
        entry("usage", "Token usage"),  // a catalog may omit the leading slash
    )

    @Test
    fun null_query_selects_nothing() {
        assertEquals(emptyList<CommandEntry>(), filterCommands(catalog, null))
    }

    @Test
    fun empty_query_selects_everything_in_catalog_order() {
        assertEquals(catalog, filterCommands(catalog, ""))
    }

    @Test
    fun prefix_match_is_case_insensitive_and_slash_insensitive() {
        assertEquals(listOf(catalog[1]), filterCommands(catalog, "MO"))
        assertEquals(listOf(catalog[1]), filterCommands(catalog, "mod"))
        assertEquals(listOf(catalog[2]), filterCommands(catalog, "st"))
        assertEquals(listOf(catalog[3]), filterCommands(catalog, "us"))
    }

    @Test
    fun no_match_yields_empty_list() {
        assertEquals(emptyList<CommandEntry>(), filterCommands(catalog, "zzz"))
    }

    @Test
    fun matching_sniffs_the_name_not_the_description() {
        assertEquals(emptyList<CommandEntry>(), filterCommands(catalog, "interrupt"))
    }

    // ---- commandDraft ----

    @Test
    fun tap_completes_with_slash_and_a_trailing_space() {
        assertEquals("/model ", commandDraft("model"))
        assertEquals("/stop ", commandDraft("/stop"))
    }

    @Test
    fun model_command_pins_the_gateway_wire_format() {
        // Same string the gateway chat sends for a SESSION-scope switch; the model comes first.
        assertEquals("/model fast --provider openrouter --session", modelCommand("openrouter", "fast"))
        assertEquals("/model m --provider /p/ --session", modelCommand("/p/", "m"))
    }
}
