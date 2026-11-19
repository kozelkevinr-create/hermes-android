package com.hermes.client.ui.plugins.workbook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbookModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesWorkbookList() {
        val body = """{"ok":true,"workbooks":[
            {"name":"budget","scope":"profile","kind":"local","file":"b.xlsx","exists":true},
            {"name":"log","scope":"global","kind":"local","file":"l.csv","exists":false},
            {"scope":"profile"}]}"""
        val list = parseWorkbooks(json, body)
        assertEquals(2, list.size)
        assertEquals("budget", list[0].name)
        assertFalse(list[0].isCsv)
        assertTrue(list[1].isCsv)
        assertFalse(list[1].exists)
        assertEquals("global", list[1].scope)
    }

    @Test fun garbageParsesToEmpty() {
        assertEquals(emptyList<WorkbookSummary>(), parseWorkbooks(json, "not json"))
        assertEquals(emptyList<WorkbookSummary>(), parseWorkbooks(json, """{"x":1}"""))
        assertEquals(WorkbookSheets(emptyList(), null), parseSheets(json, "[]"))
    }

    @Test fun parsesSheetsAndRevision() {
        val s = parseSheets(json, """{"ok":true,"sheets":["A","B"],"revision":"1f-2a"}""")
        assertEquals(listOf("A", "B"), s.sheets)
        assertEquals("1f-2a", s.revision)
        assertNull(parseSheets(json, """{"sheets":["A"]}""").revision)
    }

    @Test fun parsesDataWithMixedCells() {
        val d = parseData(
            json,
            """{"ok":true,"rows":[["item","qty"],["apple",3],[null,2.5],[true,"x"]],"truncated":true,"revision":"r1"}""",
        )
        assertEquals(listOf(listOf("item", "qty"), listOf("apple", "3"), listOf("", "2.5"), listOf("true", "x")), d.rows)
        assertTrue(d.truncated)
        assertEquals("r1", d.revision)
        assertEquals(2, d.width)
    }

    @Test fun raggedRowsUseWidestRow() {
        assertEquals(3, parseData(json, """{"rows":[["a"],["a","b","c"]]}""").width)
        assertEquals(0, WorkbookData(emptyList(), false, null).width)
    }

    @Test fun errorWithPluginCode() {
        val e = parseError(json, 409, """{"ok":false,"code":"conflict","error":"changed","revision":"r9"}""")
        assertTrue(e.isConflict)
        assertEquals("r9", e.revision)
        assertEquals("changed", e.message)
    }

    @Test fun hostNotFoundMeansRouteMissing() {
        val e = parseError(json, 404, """{"detail":"Not Found"}""")
        assertTrue(e.isRouteMissing)
        assertEquals("Not Found", e.message)
        assertTrue(parseError(json, 405, "").isRouteMissing)
    }

    @Test fun pluginNotFoundIsNotRouteMissing() {
        val e = parseError(json, 404, """{"ok":false,"code":"not_found","error":"no workbook named 'x'"}""")
        assertFalse(e.isRouteMissing)
        assertEquals("not_found", e.code)
    }

    @Test fun authAndFallbackErrors() {
        assertEquals("unauthorized", parseError(json, 401, """{"detail":"Unauthorized"}""").code)
        assertEquals("http_500", parseError(json, 500, "<html>").code)
        assertEquals("HTTP 500", parseError(json, 500, "<html>").message)
    }

    @Test fun longErrorMessagesAreTruncated() {
        val long = "x".repeat(500)
        assertEquals(200, parseError(json, 400, """{"error":"$long"}""").message?.length)
    }

    @Test fun columnLabelsAndAddresses() {
        assertEquals("A", columnLabel(0))
        assertEquals("Z", columnLabel(25))
        assertEquals("AA", columnLabel(26))
        assertEquals("AZ", columnLabel(51))
        assertEquals("BA", columnLabel(52))
        assertEquals("A1", cellAddress(0, 0))
        assertEquals("C10", cellAddress(9, 2))
    }

    @Test fun textBecomesTypedCells() {
        assertEquals(JsonNull, textToCell("  "))
        assertEquals(JsonPrimitive(3L), textToCell("3"))
        assertEquals(JsonPrimitive(-2L), textToCell("-2"))
        assertEquals(JsonPrimitive(2.5), textToCell("2.5"))
        assertEquals(JsonPrimitive(true), textToCell("TRUE"))
        assertEquals(JsonPrimitive(false), textToCell("false"))
    }

    @Test fun textThatLooksNumericButIsNotStaysString() {
        assertEquals(JsonPrimitive("007"), textToCell("007"))
        assertEquals(JsonPrimitive("1e5"), textToCell("1e5"))
        assertEquals(JsonPrimitive("12abc"), textToCell("12abc"))
        assertEquals(JsonPrimitive("=1+1"), textToCell("=1+1"))
        assertEquals(JsonPrimitive("99999999999999999999"), textToCell("99999999999999999999"))
    }

    @Test fun failureMessages() {
        assertTrue(describeFailure(WorkbookApiException(404, "route_missing", "x")).contains("not available"))
        assertTrue(describeFailure(WorkbookApiException(0, "network", "x")).contains("reach"))
        assertEquals("bad sheet", describeFailure(WorkbookApiException(400, "bad_request", "bad sheet")))
        assertEquals("Something went wrong.", describeFailure(IllegalStateException("secret")))
    }
}
