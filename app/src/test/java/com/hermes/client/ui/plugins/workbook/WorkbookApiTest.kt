package com.hermes.client.ui.plugins.workbook

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class WorkbookApiTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(token: String = "tok", baseUrl: String? = null): WorkbookApi {
        val base = baseUrl ?: serverRule.server.url("/").toString().trimEnd('/')
        val store = object : CredentialStore {
            override fun load() = GatewayConfig(baseUrl = base, token = token)
            override fun save(config: GatewayConfig) = Unit
            override fun clear() = Unit
        }
        return WorkbookApi(OkHttpClient(), json, store)
    }

    private fun reply(code: Int, body: String) =
        serverRule.server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun listSendsTokenScopeAndProfile() = runBlocking {
        reply(200, """{"ok":true,"workbooks":[{"name":"b","scope":"global","kind":"local","file":"b.xlsx","exists":true}]}""")
        val list = api().workbooks("global", "coding")
        assertEquals(1, list.size)
        val req = serverRule.server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/plugins/hermes-workbook/workbooks", req.url.encodedPath)
        assertEquals("global", req.url.queryParameter("scope"))
        assertEquals("coding", req.url.queryParameter("profile"))
        assertEquals("tok", req.headers["X-Hermes-Session-Token"])
    }

    @Test fun noTokenMeansNoHeaderAndBlankParamsAreOmitted() = runBlocking {
        reply(200, """{"ok":true,"workbooks":[]}""")
        api(token = "").workbooks(null, null)
        val req = serverRule.server.takeRequest()
        assertNull(req.headers["X-Hermes-Session-Token"])
        assertNull(req.url.queryParameter("scope"))
        assertNull(req.url.queryParameter("profile"))
    }

    @Test fun workbookNamesArePathEncoded() = runBlocking {
        reply(200, """{"ok":true,"sheets":["A"],"revision":"r"}""")
        api().sheets("my book/../x", "profile", null)
        val req = serverRule.server.takeRequest()
        assertEquals("/api/plugins/hermes-workbook/workbooks/my%20book%2F..%2Fx/sheets", req.url.encodedPath)
    }

    @Test fun dataReturnsRowsAndRevision() = runBlocking {
        reply(200, """{"ok":true,"rows":[["a",1]],"row_count":1,"truncated":false,"revision":"r2"}""")
        val d = api().data("b", "profile", "Data", "work")
        assertEquals(listOf(listOf("a", "1")), d.rows)
        assertEquals("r2", d.revision)
        val req = serverRule.server.takeRequest()
        assertEquals("Data", req.url.queryParameter("sheet"))
    }

    @Test fun writePostsTypedValuesAndIfRevision() = runBlocking {
        reply(200, """{"ok":true,"cells_written":1,"revision":"r3"}""")
        val rev = api().write("b", "profile", "A", "B2", jsonRows(listOf(listOf("42"))), "r2", "work")
        assertEquals("r3", rev)
        val req = serverRule.server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/plugins/hermes-workbook/workbooks/b/write", req.url.encodedPath)
        val sent = json.parseToJsonElement(req.body!!.utf8()).toString()
        assertTrue(sent, sent.contains("\"start\":\"B2\""))
        assertTrue(sent, sent.contains("\"values\":[[42]]"))
        assertTrue(sent, sent.contains("\"if_revision\":\"r2\""))
        assertTrue(sent, sent.contains("\"scope\":\"profile\""))
    }

    @Test fun appendOmitsRevisionWhenUnknown() = runBlocking {
        reply(200, """{"ok":true,"rows_appended":1}""")
        val rev = api().append("b", "global", null, jsonRows(listOf(listOf("x", ""))), null, null)
        assertNull(rev)
        val sent = serverRule.server.takeRequest().body!!.utf8()
        assertFalse(sent, sent.contains("if_revision"))
        assertFalse(sent, sent.contains("\"sheet\""))
        assertTrue(sent, sent.contains("\"rows\":[[\"x\",null]]"))
    }

    @Test fun createPostsNamePathScope() = runBlocking {
        reply(200, """{"ok":true,"name":"n","scope":"global","revision":"r"}""")
        assertEquals("r", api().create("n", "n.csv", "global", null))
        val sent = serverRule.server.takeRequest().body!!.utf8()
        assertTrue(sent, sent.contains("\"path\":\"n.csv\""))
    }

    @Test fun conflictCarriesCurrentRevision() = runBlocking {
        reply(409, """{"ok":false,"code":"conflict","error":"workbook changed since it was read","revision":"r9"}""")
        try {
            api().write("b", "profile", null, "A1", jsonRows(listOf(listOf("x"))), "old", null)
            fail("expected conflict")
        } catch (e: WorkbookApiException) {
            assertTrue(e.isConflict)
            assertEquals("r9", e.revision)
            assertEquals(409, e.httpStatus)
        }
    }

    @Test fun missingRouteIsDetected() = runBlocking {
        reply(404, """{"detail":"Not Found"}""")
        try {
            api().workbooks(null, null)
            fail("expected failure")
        } catch (e: WorkbookApiException) {
            assertTrue(e.isRouteMissing)
        }
    }

    @Test fun unreachableGatewayGivesGenericNetworkError() = runBlocking {
        try {
            api(baseUrl = "http://127.0.0.1:1").workbooks(null, null)
            fail("expected failure")
        } catch (e: WorkbookApiException) {
            assertEquals("network", e.code)
            assertFalse(e.message.orEmpty().contains("127.0.0.1"))
        }
    }

    @Test fun noGatewayConfigured() = runBlocking {
        val store = object : CredentialStore {
            override fun load(): GatewayConfig? = null
            override fun save(config: GatewayConfig) = Unit
            override fun clear() = Unit
        }
        try {
            WorkbookApi(OkHttpClient(), json, store).workbooks(null, null)
            fail("expected failure")
        } catch (e: WorkbookApiException) {
            assertEquals("no_gateway", e.code)
        }
    }
}
