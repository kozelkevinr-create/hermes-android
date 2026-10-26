package com.hermes.client.data.repository

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalInboxTest {
    private fun approval(rpc: String, req: String, sid: String = "s1") = ServerEvent(
        "approval.request", sid,
        buildJsonObject { put("rpc_id", rpc); put("request_id", req); put("command", "rm -rf x") },
    )

    private fun cancel(rpc: String, method: String = "approval") = ServerEvent(
        "request.cancel", "s1", buildJsonObject { put("id", rpc); put("method", method); put("reason", "resolved") },
    )

    @Test fun adds_approvals_in_arrival_order() {
        val list = emptyList<PendingApproval>()
            .applyApprovalEvent(approval("srq-1", "r1"), 1)
            .applyApprovalEvent(approval("srq-2", "r2"), 2)
        assertEquals(listOf("srq-1", "srq-2"), list.map { it.rpcId })
    }

    @Test fun replayed_approval_is_not_duplicated() {
        val list = emptyList<PendingApproval>()
            .applyApprovalEvent(approval("srq-1", "r1"), 1)
            .applyApprovalEvent(approval("srq-9", "r1"), 2)
        assertEquals(1, list.size)
    }

    @Test fun cancel_removes_only_the_named_approval() {
        val list = emptyList<PendingApproval>()
            .applyApprovalEvent(approval("srq-1", "r1"), 1)
            .applyApprovalEvent(approval("srq-2", "r2"), 2)
            .applyApprovalEvent(cancel("srq-1"), 3)
        assertEquals(listOf("srq-2"), list.map { it.rpcId })
    }

    @Test fun cancel_of_a_clarify_leaves_approvals_alone() {
        val list = emptyList<PendingApproval>()
            .applyApprovalEvent(approval("srq-1", "r1"), 1)
            .applyApprovalEvent(cancel("srq-1", method = "clarify"), 2)
        assertEquals(1, list.size)
    }

    @Test fun approval_without_a_session_is_ignored() {
        val ev = ServerEvent("approval.request", null, buildJsonObject { put("request_id", "r1") })
        assertEquals(0, emptyList<PendingApproval>().applyApprovalEvent(ev, 1).size)
    }
}
