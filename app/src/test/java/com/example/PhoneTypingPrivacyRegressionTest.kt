package com.example

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.agent.tools.AgentApprovalCoordinator
import com.example.agent.tools.PhoneUseToolSet
import com.example.agent.tools.ToolCallTrace
import com.example.agent.tools.ToolExecutionMonitor
import com.example.data.MobHarnRepository
import com.example.data.db.MobHarnDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import com.example.data.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneTypingPrivacyRegressionTest {

    private lateinit var context: Context
    private lateinit var db: MobHarnDatabase
    private lateinit var repository: MobHarnRepository
    private lateinit var approvalCoordinator: AgentApprovalCoordinator
    private lateinit var monitor: ToolExecutionMonitor
    private lateinit var phoneUse: PhoneUseToolSet

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MobHarnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vault = MarkdownVaultManager(context)
        val workspace = WorkspaceManager(context)
        val prefs = PreferencesRepository(context)
        repository = MobHarnRepository(context, db, vault, workspace, prefs)
        approvalCoordinator = AgentApprovalCoordinator()
        monitor = ToolExecutionMonitor(repository, approvalCoordinator)
        phoneUse = PhoneUseToolSet(monitor)
    }

    private fun attachObservedInputScreen(fieldHint: String?, focused: Boolean = false): MobHarnAccessibilityService {
        val svc = Robolectric.buildService(MobHarnAccessibilityService::class.java).create().get()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "org.fixture.editor"
            className = "android.widget.LinearLayout"
            setBoundsInScreen(Rect(0, 0, 1080, 1920))
            isEnabled = true
            isVisibleToUser = true
        }
        val input = AccessibilityNodeInfo.obtain().apply {
            packageName = "org.fixture.editor"
            className = "android.widget.EditText"
            if (!fieldHint.isNullOrBlank()) hintText = fieldHint
            setBoundsInScreen(Rect(40, 200, 1000, 340))
            isEditable = true
            isFocused = focused
            isEnabled = true
            isVisibleToUser = true
        }
        shadowOf(root).addChild(input)
        svc.testRootProvider = { root }
        svc.testWindowTypeProvider = { AccessibilityWindowInfo.TYPE_APPLICATION }
        svc.testDeviceLockedProvider = { false }
        svc.testActionPerformer = { _, _, _ -> true }
        svc.testPause = { }
        MobHarnAccessibilityService.setTestInstance(svc)
        svc.captureScreenTree()
        return svc
    }

    @Test fun coordinatesWithoutATargetFailWithoutPrompting() = runBlocking {
        val traces = mutableListOf<ToolCallTrace>()
        monitor.onLiveTraceUpdate = { traces.add(it) }
        phoneUse.tap_screen_coords(4, 27)
        assertEquals("error", traces.last().status)
        assertEquals(null, approvalCoordinator.pendingApproval.value)
    }

    @Test fun typingWithoutObservedUniqueTargetFailsWithoutPromptingOrLeakingSecret() = runBlocking {
        val secret = "HARMLESS_NO_TARGET_SECRET_42"
        val traces = mutableListOf<ToolCallTrace>()
        monitor.onLiveTraceUpdate = { traces.add(it) }

        val out = phoneUse.type_screen_text(fieldHint = "Notes", text = secret)

        assertEquals("error", traces.last().status)
        assertNull(approvalCoordinator.pendingApproval.value)
        assertFalse(out.contains(secret))
        traces.forEach {
            assertFalse(it.argumentsSummary.contains(secret))
            assertFalse(it.resultSummary.contains(secret))
        }
    }

    @After
    fun tearDown() {
        MobHarnAccessibilityService.setTestInstance(null)
        db.close()
    }

    @Test
    fun `type_screen_text refusal keeps marker in transient approval but excludes from saved history and traces`() = runBlocking {
        attachObservedInputScreen("Notes")
        val harmlessMarker = "HARMLESS_SECRET_REFUSAL_xyz987"
        val traces = mutableListOf<ToolCallTrace>()
        monitor.onLiveTraceUpdate = { traces.add(it) }

        val deferredResult = async(Dispatchers.Default) {
            phoneUse.type_screen_text(fieldHint = "Notes", text = harmlessMarker)
        }

        // Wait until transient approval is requested
        while (approvalCoordinator.pendingApproval.value == null) {
            kotlinx.coroutines.delay(10)
        }

        val pending = approvalCoordinator.pendingApproval.value
        assertNotNull("Transient approval prompt must be present", pending)
        // Transient prompt displays the text so user knows what action they are reviewing
        assertTrue(
            "Transient prompt description should include text for approval decision",
            pending!!.description.contains(harmlessMarker)
        )
        // Arguments summary stored in trace must NOT contain the secret value
        assertFalse(
            "Arguments summary in pending approval must NOT contain secret text",
            pending.argumentsSummary.contains(harmlessMarker)
        )
        assertEquals("type in 'Notes'", pending.argumentsSummary)

        // User refuses
        approvalCoordinator.resolve(pending.id, false)

        val result = deferredResult.await()
        assertEquals("Azione rifiutata dall'utente.", result)

        // Verify traces
        assertTrue("Live traces should be recorded", traces.isNotEmpty())
        for (trace in traces) {
            assertFalse(
                "Live trace argumentsSummary must NOT contain secret text: ${trace.argumentsSummary}",
                trace.argumentsSummary.contains(harmlessMarker)
            )
            assertFalse(
                "Live trace resultSummary must NOT contain secret text: ${trace.resultSummary}",
                trace.resultSummary.contains(harmlessMarker)
            )
        }

        // Verify database logs
        val logs = repository.toolLogsFlow.first()
        assertTrue("Should have logged the tool execution", logs.isNotEmpty())
        val lastLog = logs.first()
        assertEquals("type_screen_text", lastLog.toolName)
        assertFalse(
            "DB log argumentsJson must NOT contain secret text: ${lastLog.argumentsJson}",
            lastLog.argumentsJson.contains(harmlessMarker)
        )
        assertFalse(
            "DB log resultPreview must NOT contain secret text: ${lastLog.resultPreview}",
            lastLog.resultPreview.contains(harmlessMarker)
        )
        assertEquals("type in 'Notes'", lastLog.argumentsJson)
        assertEquals("Azione rifiutata dall'utente.", lastLog.resultPreview)
    }

    @Test
    fun `type_screen_text approval executes but excludes marker from saved traces and history`() = runBlocking {
        attachObservedInputScreen("Subject")
        val harmlessMarker = "HARMLESS_SECRET_APPROVAL_abc123"
        val traces = mutableListOf<ToolCallTrace>()
        monitor.onLiveTraceUpdate = { traces.add(it) }

        val deferredResult = async(Dispatchers.Default) {
            phoneUse.type_screen_text(fieldHint = "Subject", text = harmlessMarker)
        }

        while (approvalCoordinator.pendingApproval.value == null) {
            kotlinx.coroutines.delay(10)
        }

        val pending = approvalCoordinator.pendingApproval.value
        assertNotNull(pending)
        assertTrue(pending!!.description.contains(harmlessMarker))
        assertFalse(pending.argumentsSummary.contains(harmlessMarker))
        assertEquals("type in 'Subject'", pending.argumentsSummary)

        // User approves
        approvalCoordinator.resolve(pending.id, true)

        val result = deferredResult.await()
        assertFalse(
            "Result string must NOT contain secret text: $result",
            result.contains(harmlessMarker)
        )

        for (trace in traces) {
            assertFalse(
                "Trace argumentsSummary must NOT contain secret text",
                trace.argumentsSummary.contains(harmlessMarker)
            )
            assertFalse(
                "Trace resultSummary must NOT contain secret text",
                trace.resultSummary.contains(harmlessMarker)
            )
        }

        val logs = repository.toolLogsFlow.first()
        assertTrue(logs.isNotEmpty())
        val lastLog = logs.first()
        assertFalse(
            "DB log argumentsJson must NOT contain secret text",
            lastLog.argumentsJson.contains(harmlessMarker)
        )
        assertFalse(
            "DB log resultPreview must NOT contain secret text",
            lastLog.resultPreview.contains(harmlessMarker)
        )
        assertEquals("type in 'Subject'", lastLog.argumentsJson)
    }

    @Test
    fun `type_screen_text with empty fieldHint stores action and active field without value`() = runBlocking {
        attachObservedInputScreen(fieldHint = null, focused = true)
        val harmlessMarker = "HARMLESS_ACTIVE_FIELD_999"
        val deferredResult = async(Dispatchers.Default) {
            phoneUse.type_screen_text(fieldHint = "", text = harmlessMarker)
        }

        while (approvalCoordinator.pendingApproval.value == null) {
            kotlinx.coroutines.delay(10)
        }

        val pending = approvalCoordinator.pendingApproval.value
        assertNotNull(pending)
        assertEquals("type in active field", pending!!.argumentsSummary)
        assertFalse(pending.argumentsSummary.contains(harmlessMarker))

        approvalCoordinator.resolve(pending.id, false)
        deferredResult.await()

        val logs = repository.toolLogsFlow.first()
        val lastLog = logs.first()
        assertEquals("type in active field", lastLog.argumentsJson)
        assertFalse(lastLog.argumentsJson.contains(harmlessMarker))
    }
}
