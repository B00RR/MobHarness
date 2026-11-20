package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import com.example.agent.automation.AutomationTaskOutcome
import com.example.agent.automation.AutomationWorker
import com.example.agent.tools.AndroidSystemActions
import com.example.agent.tools.WebResult
import com.example.data.db.AutomationTaskEntity
import com.example.di.MobHarnAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomationWorkerOutcomeRegressionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `web monitor failure with blank query produces failure outcome and non-success notification and db result`() = runBlocking {
        val container = MobHarnAppContainer.getInstance(context)
        val db = container.database
        val taskId = db.automationDao().upsertTask(
            AutomationTaskEntity(
                name = "Blank Web Search",
                prompt = "   ",
                actionType = "WEB_MONITOR",
                targetUrl = "",
                workTag = "test_tag_blank"
            )
        )
        val task = requireNotNull(db.automationDao().getTaskById(taskId))

        var notifiedId: Int? = null
        var notifiedTitle: String? = null
        var notifiedBody: String? = null

        val result = AutomationWorker.executeTask(
            task = task,
            system = container.systemActions,
            vault = container.vaultManager,
            workspace = container.workspaceManager,
            db = db,
            onNotification = { id, title, body ->
                notifiedId = id
                notifiedTitle = title
                notifiedBody = body
            }
        )

        assertEquals("Failed web task must yield Result.failure()", Result.failure(), result)
        assertNotEquals("Failed web task must never yield Result.success()", Result.success(), result)
        assertNotEquals("Failed uncertain action must never yield Result.retry()", Result.retry(), result)

        val updatedTask = requireNotNull(db.automationDao().getTaskById(taskId))
        assertTrue("DB lastResult must capture the failure message", updatedTask.lastResult.contains("Errore durante automazione 'Blank Web Search'"))
        assertTrue("DB lastResult must contain query failure details", updatedTask.lastResult.contains("Query di ricerca vuota"))

        assertEquals(task.id.toInt(), notifiedId)
        assertEquals("Blank Web Search", notifiedTitle)
        assertNotNull(notifiedBody)
        assertTrue("Notification body must capture the failure", notifiedBody!!.contains("Errore durante automazione 'Blank Web Search'"))
    }

    @Test
    fun `web monitor network failure produces failure outcome without localized string parsing`() = runBlocking {
        val container = MobHarnAppContainer.getInstance(context)
        val db = container.database
        val taskId = db.automationDao().upsertTask(
            AutomationTaskEntity(
                name = "Unreachable Web Monitor",
                prompt = "",
                actionType = "WEB_MONITOR",
                targetUrl = "http://127.0.0.1:1/nonexistent",
                workTag = "test_tag_unreachable"
            )
        )
        val task = requireNotNull(db.automationDao().getTaskById(taskId))

        val result = AutomationWorker.executeTask(
            task = task,
            system = container.systemActions,
            vault = container.vaultManager,
            workspace = container.workspaceManager,
            db = db
        )

        assertEquals("Network error in web monitor must return Result.failure()", Result.failure(), result)
        assertNotEquals(Result.success(), result)
        assertNotEquals(Result.retry(), result)

        val updatedTask = requireNotNull(db.automationDao().getTaskById(taskId))
        assertTrue(updatedTask.lastResult.contains("Errore durante automazione 'Unreachable Web Monitor'"))
    }

    @Test
    fun `uncertain action failure is never retried`() {
        val failureOutcome = AutomationTaskOutcome.Failure("Qualcosa è andato storto", IllegalStateException("Timeout"))
        val workResult = AutomationWorker.evaluateOutcomeResult(failureOutcome)

        assertEquals("Uncertain failed action must return Result.failure()", Result.failure(), workResult)
        assertNotEquals("Uncertain action must NEVER be retried to prevent inconsistent side-effects", Result.retry(), workResult)
        assertNotEquals("Failure must never claim success", Result.success(), workResult)

        val successOutcome = AutomationTaskOutcome.Success("Report completato con successo")
        assertEquals(Result.success(), AutomationWorker.evaluateOutcomeResult(successOutcome))
    }

    @Test
    fun `coroutine cancellation propagates cleanly without updating database or posting notification`() = runBlocking {
        val container = MobHarnAppContainer.getInstance(context)
        val db = container.database
        val initialResult = "Initial Result State"
        val taskId = db.automationDao().upsertTask(
            AutomationTaskEntity(
                name = "Cancellable Task",
                prompt = "Test prompt",
                actionType = "WORKSPACE_AUDIT",
                lastResult = initialResult,
                workTag = "test_cancellable"
            )
        )
        val task = requireNotNull(db.automationDao().getTaskById(taskId))

        var notificationPosted = false
        val cancelledJob = Job().apply { cancel(CancellationException("Simulated job cancellation")) }

        try {
            withContext(cancelledJob) {
                AutomationWorker.executeTask(
                    task = task,
                    system = container.systemActions,
                    vault = container.vaultManager,
                    workspace = container.workspaceManager,
                    db = db,
                    onNotification = { _, _, _ -> notificationPosted = true }
                )
            }
            fail("CancellationException must propagate out of executeTask")
        } catch (e: CancellationException) {
            assertEquals("Simulated job cancellation", e.message)
        }

        assertFalse("Notification must NOT be posted when coroutine is cancelled", notificationPosted)

        val taskAfterCancel = requireNotNull(db.automationDao().getTaskById(taskId))
        assertEquals("Database must NOT be updated with partial/cancellation result", initialResult, taskAfterCancel.lastResult)
    }

    @Test
    fun `shared typed web outcome correctly models failures as exceptions without parsing`() = runBlocking {
        val actions = AndroidSystemActions(context)

        val invalidUrlOutcome = actions.fetchWebpageOutcome("   ")
        assertTrue("Empty URL must yield WebResult.Failure", invalidUrlOutcome is WebResult.Failure.InvalidUrl)
        assertTrue("Failure must be an Exception instance", invalidUrlOutcome is Exception)
        assertEquals("URL non valido o vuoto.", invalidUrlOutcome.text)

        val emptyQueryOutcome = actions.webSearchOutcome("")
        assertTrue("Empty query must yield WebResult.Failure.EmptyQuery", emptyQueryOutcome is WebResult.Failure.EmptyQuery)
        assertTrue("Failure must be an Exception instance", emptyQueryOutcome is Exception)
        assertEquals("Query di ricerca vuota.", emptyQueryOutcome.text)

        try {
            actions.fetchWebpageOrThrow("")
            fail("fetchWebpageOrThrow must throw WebResult.Failure on invalid url")
        } catch (e: WebResult.Failure) {
            assertTrue(e is WebResult.Failure.InvalidUrl)
        }

        try {
            actions.webSearchOrThrow("   ")
            fail("webSearchOrThrow must throw WebResult.Failure on blank query")
        } catch (e: WebResult.Failure) {
            assertTrue(e is WebResult.Failure.EmptyQuery)
        }

        // Standard string methods continue to return expected display strings without throwing
        assertEquals("URL non valido o vuoto.", actions.fetchWebpage(""))
        assertEquals("Query di ricerca vuota.", actions.webSearch(""))
    }

    @Test
    fun `successful telemetry automation updates db and notification and returns Result success`() = runBlocking {
        val container = MobHarnAppContainer.getInstance(context)
        val db = container.database
        val taskId = db.automationDao().upsertTask(
            AutomationTaskEntity(
                name = "Telemetry Check",
                prompt = "",
                actionType = "TELEMETRY_CHECK",
                workTag = "test_telemetry"
            )
        )
        val task = requireNotNull(db.automationDao().getTaskById(taskId))

        var notifiedTitle: String? = null
        var notifiedBody: String? = null

        val result = AutomationWorker.executeTask(
            task = task,
            system = container.systemActions,
            vault = container.vaultManager,
            workspace = container.workspaceManager,
            db = db,
            onNotification = { _, title, body ->
                notifiedTitle = title
                notifiedBody = body
            }
        )

        assertEquals("Successful automation must return Result.success()", Result.success(), result)

        val updatedTask = requireNotNull(db.automationDao().getTaskById(taskId))
        assertTrue("DB lastResult must contain telemetry report", updatedTask.lastResult.contains("Dispositivo:"))
        assertNotNull(updatedTask.lastRunAt)

        assertEquals("Telemetry Check", notifiedTitle)
        assertNotNull(notifiedBody)
        assertTrue(notifiedBody!!.contains("Dispositivo:"))
    }
}
