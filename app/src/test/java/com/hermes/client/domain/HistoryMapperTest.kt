package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMapperTest {
    private fun dto(json: String) = Json { ignoreUnknownKeys = true }.decodeFromString<MessageDto>(json)

    private val call = """{"id":1,"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{}"}}]}"""
    private val result = """{"id":2,"role":"tool","tool_call_id":"c1","tool_name":"terminal","content":"{\"output\":\"hello\",\"exit_code\":0}"}"""

    @Test fun tool_rows_fill_the_cards_of_the_call_row() {
        val h = listOf(dto(call), dto(result), dto("""{"id":3,"role":"assistant","content":"done"}""")).toHistory()
        assertEquals(2, h.size)
        assertEquals(listOf(ToolCall("c1", "terminal", ToolStatus.DONE, "hello")), h[0].tools)
        assertEquals(Role.ASSISTANT, h[0].role)
        assertEquals("done", h[1].text)
    }

    @Test fun tool_calls_serialised_as_a_string_are_read_too() {
        val stringified = """{"id":1,"role":"assistant","content":"","tool_calls":"[{\"id\":\"c9\",\"function\":{\"name\":\"read_file\"}}]"}"""
        assertEquals("read_file", listOf(dto(stringified)).toHistory()[0].tools.single().name)
    }

    @Test fun orphan_tool_row_still_shows_as_a_card_and_not_as_text() {
        val h = listOf(dto(result)).toHistory()
        assertEquals(Role.ASSISTANT, h.single().role)
        assertEquals("terminal", h.single().tools.single().name)
        assertTrue(h.single().text.isEmpty())
    }

    @Test fun plain_output_and_hidden_rows() {
        val raw = """{"id":2,"role":"tool","tool_call_id":"c1","content":"not json"}"""
        val hidden = """{"id":5,"role":"user","content":"scaffold","display_kind":"hidden"}"""
        val h = listOf(dto(call), dto(raw), dto(hidden)).toHistory()
        assertEquals("not json", h.single().tools.single().output)
    }

    @Test fun ids_are_unique_even_when_message_ids_repeat() {
        val h = listOf(dto("""{"id":1,"role":"user","content":"a"}"""), dto("""{"id":1,"role":"user","content":"b"}""")).toHistory()
        assertEquals(2, h.map { it.id }.toSet().size)
    }
}
