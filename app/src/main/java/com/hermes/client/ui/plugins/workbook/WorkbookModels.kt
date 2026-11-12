package com.hermes.client.ui.plugins.workbook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One entry of `GET /workbooks`. `file` is a bare file name, never a path. */
data class WorkbookSummary(
    val name: String,
    val scope: String,
    val kind: String,
    val file: String,
    val exists: Boolean,
) {
    val isCsv: Boolean get() = file.endsWith(".csv", ignoreCase = true)
}

data class WorkbookSheets(val sheets: List<String>, val revision: String?)

/** Cell values are already formatted for display ("" for empty). */
data class WorkbookData(
    val rows: List<List<String>>,
    val truncated: Boolean,
    val revision: String?,
) {
    val width: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

/**
 * A failed call. [code] is the plugin's own error code (`bad_request`, `not_found`,
 * `conflict`, `internal`) or a client-side one: `route_missing` (the gateway has no such
 * route, i.e. plugin not installed or older), `network`, `no_gateway`.
 */
class WorkbookApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
    val revision: String? = null,
) : Exception(message) {
    val isConflict: Boolean get() = code == "conflict"
    val isRouteMissing: Boolean get() = code == "route_missing"
}

private const val MAX_ERROR_LENGTH = 200

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun parseObject(json: Json, body: String): JsonObject? =
    runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

/** Display text for one JSON cell; null becomes "". */
internal fun cellDisplay(element: JsonElement): String = when (element) {
    is JsonNull -> ""
    is JsonPrimitive -> element.content
    else -> element.toString()
}

internal fun parseWorkbooks(json: Json, body: String): List<WorkbookSummary> {
    val array = parseObject(json, body)?.get("workbooks") as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val name = o["name"].text() ?: return@mapNotNull null
        WorkbookSummary(
            name = name,
            scope = o["scope"].text() ?: "profile",
            kind = o["kind"].text() ?: "local",
            file = o["file"].text() ?: "",
            exists = (o["exists"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }
}

internal fun parseSheets(json: Json, body: String): WorkbookSheets {
    val o = parseObject(json, body)
    val names = (o?.get("sheets") as? JsonArray)?.mapNotNull { it.text() } ?: emptyList()
    return WorkbookSheets(names, o?.get("revision").text())
}

internal fun parseData(json: Json, body: String): WorkbookData {
    val o = parseObject(json, body)
    val rows = (o?.get("rows") as? JsonArray)?.map { row ->
        (row as? JsonArray)?.map(::cellDisplay) ?: emptyList()
    } ?: emptyList()
    val truncated = (o?.get("truncated") as? JsonPrimitive)?.booleanOrNull ?: false
    return WorkbookData(rows, truncated, o?.get("revision").text())
}

internal fun parseRevision(json: Json, body: String): String? =
    parseObject(json, body)?.get("revision").text()

/** Maps an HTTP failure to a [WorkbookApiException]; never echoes more than a short message. */
internal fun parseError(json: Json, status: Int, body: String): WorkbookApiException {
    val o = parseObject(json, body)
    val code = o?.get("code").text()
    val message = (o?.get("error").text() ?: o?.get("detail").text())
        ?.take(MAX_ERROR_LENGTH)
        ?: "HTTP $status"
    val resolved = code ?: when (status) {
        404, 405 -> "route_missing"
        401, 403 -> "unauthorized"
        else -> "http_$status"
    }
    return WorkbookApiException(status, resolved, message, o?.get("revision").text())
}

/** "A", "B", ... "Z", "AA" for a zero-based column index. */
internal fun columnLabel(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        sb.insert(0, ('A' + rem))
        n = (n - 1) / 26
    }
    return sb.toString()
}

/** A1 address for zero-based [row] and [col]. */
internal fun cellAddress(row: Int, col: Int): String = columnLabel(col) + (row + 1)

private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")

/**
 * What the user typed, as a JSON cell: blank -> null, plain decimal numbers -> numbers,
 * true/false -> booleans, everything else (including "007" and huge integers) -> string.
 */
internal fun textToCell(text: String): JsonElement {
    val t = text.trim()
    if (t.isEmpty()) return JsonNull
    if (t.equals("true", ignoreCase = true)) return JsonPrimitive(true)
    if (t.equals("false", ignoreCase = true)) return JsonPrimitive(false)
    if (NUMBER.matches(t)) {
        if ('.' !in t) {
            // Integers beyond Long range stay strings so no digits are lost.
            t.toLongOrNull()?.let { return JsonPrimitive(it) }
        } else {
            t.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return JsonPrimitive(it) }
        }
    }
    return JsonPrimitive(text)
}

/** Short user-facing text for a failed call. */
internal fun describeFailure(e: Throwable): String = when {
    e is WorkbookApiException && e.isRouteMissing ->
        "The Workbook plugin is not available on this gateway (or is an older version)."
    e is WorkbookApiException && e.code == "unauthorized" -> "Not authorized. Check the connection settings."
    e is WorkbookApiException && e.code == "network" -> "Could not reach the gateway."
    e is WorkbookApiException -> e.message ?: "Request failed"
    else -> "Something went wrong."
}
