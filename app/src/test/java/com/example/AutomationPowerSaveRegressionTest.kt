package com.example

import androidx.work.ListenableWorker
import com.example.agent.automation.AutomationWorker
import com.example.data.db.AutomationTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationPowerSaveRegressionTest {

    @Test
    fun `periodic automation in power-save mode returns success preserving periodic skip policy`() {
        val result = AutomationWorker.evaluatePowerSaveAction(
            isManual = false,
            isPowerSaveMode = true,
            isPeriodic = true
        )

        assertNotNull("Periodic task in power save should yield an action", result)
        assertEquals(
            "Periodic automation must return Result.success() to skip the current tick cleanly",
            ListenableWorker.Result.success(),
            result
        )
    }

    @Test
    fun `one-time automation in power-save mode returns retry to defer with backoff`() {
        val result = AutomationWorker.evaluatePowerSaveAction(
            isManual = false,
            isPowerSaveMode = true,
            isPeriodic = false
        )

        assertNotNull("One-time task in power save should yield an action", result)
        assertEquals(
            "One-time automation must return Result.retry() to prevent disappearing without execution",
            ListenableWorker.Result.retry(),
            result
        )
        assertNotEquals(
            "One-time automation must NEVER return Result.success() when skipped for power saving",
            ListenableWorker.Result.success(),
            result
        )
    }

    @Test
    fun `automations when power-save mode is disabled are not skipped`() {
        val periodicResult = AutomationWorker.evaluatePowerSaveAction(
            isManual = false,
            isPowerSaveMode = false,
            isPeriodic = true
        )
        assertNull("Periodic task must not be deferred when power save is off", periodicResult)

        val oneTimeResult = AutomationWorker.evaluatePowerSaveAction(
            isManual = false,
            isPowerSaveMode = false,
            isPeriodic = false
        )
        assertNull("One-time task must not be deferred when power save is off", oneTimeResult)
    }

    @Test
    fun `manual automation run proceeds even when power-save mode is enabled`() {
        val manualOneTime = AutomationWorker.evaluatePowerSaveAction(
            isManual = true,
            isPowerSaveMode = true,
            isPeriodic = false
        )
        assertNull("Manual execution must ignore power save constraints", manualOneTime)

        val manualPeriodic = AutomationWorker.evaluatePowerSaveAction(
            isManual = true,
            isPowerSaveMode = true,
            isPeriodic = true
        )
        assertNull("Manual execution of periodic task must ignore power save constraints", manualPeriodic)
    }
}
