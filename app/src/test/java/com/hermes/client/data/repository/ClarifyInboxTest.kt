package com.hermes.client.data.repository

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ClarifyInboxTest {
    private fun clarify(id: String, q: String = "which one?", sid: String = "s1") = ServerEvent(
        "clarify.request", sid, buildJsonObject { put("request_id", id); put("question", q) },
    )

    private fun cancel(id: String, method: String = "clarify") = ServerEvent(
        "request.cancel", "s1", buildJsonObject { put("id", id); put("method", method) },
    )

    @Test fun adds_questions_in_arrival_order() {
        val list = emptyList<PendingClarify>()
            .applyClarifyEvent(clarify("srq-1"), 1)
            .applyClarifyEvent(clarify("srq-2"), 2)
        assertEquals(listOf("srq-1", "srq-2"), list.map { it.requestId })
    }

    @Test fun replayed_question_is_not_duplicated() {
        val list = emptyList<PendingClarify>()
            .applyClarifyEvent(clarify("srq-1"), 1)
            .applyClarifyEvent(clarify("srq-1"), 2)
        assertEquals(1, list.size)
    }

    @Test fun cancel_removes_only_the_named_clarify() {
        val list = emptyList<PendingClarify>()
            .applyClarifyEvent(clarify("srq-1"), 1)
            .applyClarifyEvent(clarify("srq-2"), 2)
            .applyClarifyEvent(cancel("srq-1"), 3)
        assertEquals(listOf("srq-2"), list.map { it.requestId })
    }

    @Test fun approval_cancel_leaves_clarifies_alone() {
        val list = emptyList<PendingClarify>()
            .applyClarifyEvent(clarify("srq-1"), 1)
            .applyClarifyEvent(cancel("srq-1", method = "approval"), 2)
        assertEquals(1, list.size)
    }
}
