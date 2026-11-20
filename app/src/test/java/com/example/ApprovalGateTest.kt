package com.example

import com.example.agent.tools.AgentApprovalCoordinator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalGateTest {
    @Test fun shadeDismissalOnlyPrecedesAValidApprovedScreenAction() = runTest {
        var unlocked = true
        val gate = AgentApprovalCoordinator(unlocked = { unlocked })
        var dismissals = 0
        for ((tool, approve) in listOf("tap_screen_text" to true, "scroll_screen" to false, "set_alarm" to true)) {
            val request = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval(tool, "test", "", "") }
            val id = gate.pendingApproval.value!!.id
            val before = dismissals
            gate.resolve("stale", true) { dismissals++ }
            unlocked = false
            gate.resolve(id, true) { dismissals++ }
            unlocked = true
            assertEquals(before, dismissals)
            gate.resolve(id, approve) { dismissals++ }
            gate.resolve(id, approve) { dismissals++ }
            assertEquals(approve, request.await())
            assertEquals(before + (if (tool == "tap_screen_text") 1 else 0), dismissals)
        }
    }

    @Test fun secondRequestCannotReplaceTheCommandBeingReviewed() = runTest {
        val gate = AgentApprovalCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval("a", "test", "A", "") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval("b", "test", "B", "") }
        try { assertEquals("a", gate.pendingApproval.value?.toolName) }
        finally { first.cancel(); second.cancel() }
    }
}
