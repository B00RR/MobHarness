package com.example

import com.example.agent.tools.AgentApprovalCoordinator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalGateTest {
    @Test fun secondRequestCannotReplaceTheCommandBeingReviewed() = runTest {
        val gate = AgentApprovalCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval("a", "test", "A", "") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval("b", "test", "B", "") }
        try { assertEquals("a", gate.pendingApproval.value?.toolName) }
        finally { first.cancel(); second.cancel() }
    }
}
