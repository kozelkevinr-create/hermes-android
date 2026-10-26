package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalQueueTest {
    private fun req(rpc: String, id: String) =
        ApprovalRequest("cmd", "desc", emptyList(), allowPermanent = true, requestId = id, rpcId = rpc)

    @Test fun second_approval_queues_behind_the_first() {
        val s = ChatUiState.empty().withApproval(req("srq-1", "r1")).withApproval(req("srq-2", "r2"))
        assertEquals("r1", s.pendingApproval!!.requestId)
        assertEquals(listOf("r2"), s.queuedApprovals.map { it.requestId })
    }

    @Test fun replayed_approval_is_not_queued_twice() {
        val s = ChatUiState.empty().withApproval(req("srq-1", "r1")).withApproval(req("srq-9", "r1"))
        assertEquals(0, s.queuedApprovals.size)
    }

    @Test fun answering_promotes_the_next_one() {
        val s = ChatUiState.empty().withApproval(req("srq-1", "r1")).withApproval(req("srq-2", "r2")).withoutApproval()
        assertEquals("r2", s.pendingApproval!!.requestId)
        assertEquals(0, s.queuedApprovals.size)
    }

    @Test fun cancelling_a_queued_approval_keeps_the_pending_one() {
        val s = ChatUiState.empty().withApproval(req("srq-1", "r1")).withApproval(req("srq-2", "r2")).withoutApproval("srq-2")
        assertEquals("r1", s.pendingApproval!!.requestId)
        assertEquals(0, s.queuedApprovals.size)
    }

    @Test fun cancelling_the_pending_approval_promotes_the_queue() {
        val s = ChatUiState.empty().withApproval(req("srq-1", "r1")).withApproval(req("srq-2", "r2")).withoutApproval("srq-1")
        assertEquals("r2", s.pendingApproval!!.requestId)
    }

    @Test fun last_approval_answered_leaves_nothing_pending() {
        assertNull(ChatUiState.empty().withApproval(req("srq-1", "r1")).withoutApproval().pendingApproval)
    }
}
