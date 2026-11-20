package com.example

import android.app.NotificationManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.agent.tools.AgentApprovalCoordinator
import com.example.agent.tools.ApprovalNotifications
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
import kotlinx.coroutines.delay
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
class ScreenTargetResolutionTest {

    private lateinit var context: Context
    private lateinit var db: MobHarnDatabase
    private lateinit var repository: MobHarnRepository
    private lateinit var approvalCoordinator: AgentApprovalCoordinator
    private lateinit var notifications: ApprovalNotifications
    private lateinit var monitor: ToolExecutionMonitor
    private lateinit var phoneUse: PhoneUseToolSet
    private lateinit var service: MobHarnAccessibilityService

    private var currentRoot: AccessibilityNodeInfo? = null
    private var deviceLocked: Boolean = false
    private val dispatchedActions = mutableListOf<Triple<AccessibilityNodeInfo, Int, Bundle?>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MobHarnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MobHarnRepository(
            context = context,
            db = db,
            vault = MarkdownVaultManager(context),
            workspace = WorkspaceManager(context),
            prefs = PreferencesRepository(context)
        )
        approvalCoordinator = AgentApprovalCoordinator(
            grants = context.getSharedPreferences("screen_target_test_grants", Context.MODE_PRIVATE).apply {
                edit().clear().commit()
            },
            unlocked = { !deviceLocked }
        )
        notifications = ApprovalNotifications(context, approvalCoordinator)
        monitor = ToolExecutionMonitor(repository, approvalCoordinator)
        phoneUse = PhoneUseToolSet(monitor)

        service = Robolectric.buildService(MobHarnAccessibilityService::class.java).create().get()
        service.testRootProvider = { currentRoot }
        service.testWindowTypeProvider = { AccessibilityWindowInfo.TYPE_APPLICATION }
        service.testDeviceLockedProvider = { deviceLocked }
        service.testPause = { }
        service.testActionPerformer = { node, action, args ->
            dispatchedActions.add(Triple(node, action, args))
            true
        }
        MobHarnAccessibilityService.setTestInstance(service)
        dispatchedActions.clear()
        deviceLocked = false
    }

    @After
    fun tearDown() {
        MobHarnAccessibilityService.setTestInstance(null)
        db.close()
    }

    private fun makeNode(
        pkg: String = "org.fixture.demo",
        className: String = "android.widget.TextView",
        text: String? = null,
        desc: String? = null,
        hint: String? = null,
        viewId: String? = null,
        bounds: Rect = Rect(0, 0, 200, 80),
        clickable: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        scrollable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        password: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.packageName = pkg
        this.className = className
        this.text = text
        this.contentDescription = desc
        if (hint != null) this.hintText = hint
        if (viewId != null) this.viewIdResourceName = viewId
        this.setBoundsInScreen(bounds)
        this.isClickable = clickable
        this.isEditable = editable
        this.isFocused = focused
        this.isScrollable = scrollable
        this.isCheckable = checkable
        @Suppress("DEPRECATION")
        this.isChecked = checked
        this.isPassword = password
        this.isEnabled = enabled
        this.isVisibleToUser = visible
    }

    private fun attach(parent: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        val shadow = shadowOf(parent)
        children.forEach { child ->
            shadow.addChild(child)
        }
    }

    private fun activeNotificationsCount(): Int {
        shadowOf(Looper.getMainLooper()).idle()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return mgr.activeNotifications.size
    }

    @Test
    fun tapTextFailsWithoutPromptOrNotificationWhenScreenWasNotRead() = runBlocking {
        val root = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 0, 1080, 1920))
        val btn = makeNode(text = "Avanti", clickable = true, bounds = Rect(100, 100, 300, 180))
        attach(root, btn)
        currentRoot = root

        val traces = mutableListOf<ToolCallTrace>()
        monitor.onLiveTraceUpdate = { traces.add(it) }

        val out = phoneUse.tap_screen_text("Avanti")

        assertEquals("error", traces.last().status)
        assertNull(approvalCoordinator.pendingApproval.value)
        assertEquals(0, activeNotificationsCount())
        assertTrue(dispatchedActions.isEmpty())
        assertTrue("Expected no-prior-read error, got: $out", out.contains("read_screen_ui", ignoreCase = true) || out.contains("lettura", ignoreCase = true))
    }

    @Test
    fun tapTextFailsWithoutPromptWhenServiceMissingOrLabelMissingOrAmbiguous() = runBlocking {
        // 1. Service disconnected
        MobHarnAccessibilityService.setTestInstance(null)
        val noSvc = phoneUse.tap_screen_text("Avanti")
        assertNull(approvalCoordinator.pendingApproval.value)
        assertTrue(noSvc.contains("Accessibilità", ignoreCase = true))
        assertTrue(dispatchedActions.isEmpty())

        // Reconnect and capture screen with duplicate labels
        MobHarnAccessibilityService.setTestInstance(service)
        val root = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 0, 1080, 1920))
        val rowA = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 100, 1080, 220))
        val titleA = makeNode(text = "Scheda alfa", bounds = Rect(20, 110, 400, 160))
        val btnA = makeNode(text = "Apri", clickable = true, bounds = Rect(700, 110, 960, 190))
        attach(rowA, titleA, btnA)

        val rowB = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 240, 1080, 360))
        val titleB = makeNode(text = "Scheda beta", bounds = Rect(20, 250, 400, 300))
        val btnB = makeNode(text = "Apri", clickable = true, bounds = Rect(700, 250, 960, 330))
        attach(rowB, titleB, btnB)

        attach(root, rowA, rowB)
        currentRoot = root
        service.captureScreenTree()

        // 2. Missing label -> zero prompts, zero gestures
        val missing = phoneUse.tap_screen_text("Inesistente")
        assertNull(approvalCoordinator.pendingApproval.value)
        assertEquals(0, activeNotificationsCount())
        assertTrue(dispatchedActions.isEmpty())
        assertTrue("Expected not found, got: $missing", missing.contains("non trovato", ignoreCase = true))

        // 3. Duplicate/ambiguous label without context -> zero prompts, zero gestures
        val ambiguous = phoneUse.tap_screen_text("Apri")
        assertNull(approvalCoordinator.pendingApproval.value)
        assertEquals(0, activeNotificationsCount())
        assertTrue(dispatchedActions.isEmpty())
        assertTrue("Expected ambiguity error, got: $ambiguous", ambiguous.contains("ambiguo", ignoreCase = true))
    }

    @Test
    fun contextualLocatorDisambiguatesDuplicateLabelsAndPromptsExactlyOnce() = runBlocking {
        val root = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 0, 1080, 1920))
        val rowA = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 100, 1080, 220))
        val titleA = makeNode(text = "Scheda alfa", bounds = Rect(20, 110, 400, 160))
        val btnA = makeNode(text = "Apri", clickable = true, bounds = Rect(700, 110, 960, 190))
        attach(rowA, titleA, btnA)

        val rowB = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 240, 1080, 360))
        val titleB = makeNode(text = "Scheda beta", bounds = Rect(20, 250, 400, 300))
        val btnB = makeNode(text = "Apri", clickable = true, bounds = Rect(700, 250, 960, 330))
        attach(rowB, titleB, btnB)

        attach(root, rowA, rowB)
        currentRoot = root
        service.captureScreenTree()

        val deferred = async(Dispatchers.Default) {
            phoneUse.tap_screen_text(targetText = "Apri", contextHint = "Scheda beta")
        }

        kotlinx.coroutines.withTimeout(5_000) { approvalCoordinator.pendingApproval.first { it != null } }
        val pending = requireNotNull(approvalCoordinator.pendingApproval.value)
        assertNull("Screen targets must never allow permanent remembered approval", pending.rememberKey)

        // Attempting to save approval must be ignored because rememberKey is null
        approvalCoordinator.resolve(pending.id, approved = true, remember = true)
        assertFalse(deferred.isCompleted)

        // Approving once dispatches click ONLY on btnB
        approvalCoordinator.resolve(pending.id, approved = true, remember = false)
        deferred.await()

        assertEquals(1, dispatchedActions.size)
        assertEquals(btnB, dispatchedActions.single().first)
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, dispatchedActions.single().second)
    }

    @Test
    fun typeAndScrollResolveUniqueTargetBeforePrompting() = runBlocking {
        val root = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 0, 1080, 1920))
        val scrollA = makeNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            desc = "Lista principale",
            scrollable = true,
            bounds = Rect(0, 100, 1080, 900)
        )
        val scrollB = makeNode(
            className = "android.widget.HorizontalScrollView",
            desc = "Barra schede",
            scrollable = true,
            bounds = Rect(0, 920, 1080, 1100)
        )
        val fieldRow = makeNode(className = "android.widget.LinearLayout", bounds = Rect(0, 1200, 1080, 1360))
        val fieldLabel = makeNode(text = "Codice pratica", bounds = Rect(20, 1210, 300, 1260))
        val editField = makeNode(
            className = "android.widget.EditText",
            editable = true,
            focused = false,
            bounds = Rect(320, 1210, 1000, 1340)
        )
        attach(fieldRow, fieldLabel, editField)
        attach(root, scrollA, scrollB, fieldRow)
        currentRoot = root
        service.captureScreenTree()

        // Invalid scroll direction -> zero prompts, zero gestures
        val badDir = phoneUse.scroll_screen(direction = "diagonal")
        assertNull(approvalCoordinator.pendingApproval.value)
        assertTrue(badDir.contains("non valida", ignoreCase = true))
        assertTrue(dispatchedActions.isEmpty())

        // Ambiguous scroll (2 scrollables without containerHint) -> zero prompts, zero gestures
        val ambigScroll = phoneUse.scroll_screen(direction = "down")
        assertNull(approvalCoordinator.pendingApproval.value)
        assertTrue(ambigScroll.contains("ambiguo", ignoreCase = true))
        assertTrue(dispatchedActions.isEmpty())

        // Contextual scroll disambiguates and prompts once
        val scrollJob = async(Dispatchers.Default) {
            phoneUse.scroll_screen(direction = "down", containerHint = "Lista principale")
        }
        kotlinx.coroutines.withTimeout(5_000) { approvalCoordinator.pendingApproval.first { it != null } }
        val scrollPending = requireNotNull(approvalCoordinator.pendingApproval.value)
        assertNull(scrollPending.rememberKey)
        approvalCoordinator.resolve(scrollPending.id, approved = true)
        scrollJob.await()
        assertEquals(1, dispatchedActions.size)
        assertEquals(scrollA, dispatchedActions.single().first)

        // Fresh read after scroll
        service.captureScreenTree()

        // Typing with empty fieldHint when NO field is focused -> zero prompts, zero gestures
        val noFocus = phoneUse.type_screen_text(fieldHint = "", text = "PRIVATE_UNFOCUSED_123")
        assertNull(approvalCoordinator.pendingApproval.value)
        assertFalse(noFocus.contains("PRIVATE_UNFOCUSED_123"))
        assertEquals(1, dispatchedActions.size)

        // Typing matching contextual row label ("Codice pratica") -> prompts once and types on editField
        val typeJob = async(Dispatchers.Default) {
            phoneUse.type_screen_text(fieldHint = "Codice pratica", text = "PRIVATE_CODE_789")
        }
        kotlinx.coroutines.withTimeout(5_000) { approvalCoordinator.pendingApproval.first { it != null } }
        val typePending = requireNotNull(approvalCoordinator.pendingApproval.value)
        assertNull(typePending.rememberKey)
        assertFalse(typePending.argumentsSummary.contains("PRIVATE_CODE_789"))
        approvalCoordinator.resolve(typePending.id, approved = true)
        val typeOut = typeJob.await()
        assertFalse(typeOut.contains("PRIVATE_CODE_789"))
        assertEquals(2, dispatchedActions.size)
        assertEquals(editField, dispatchedActions.last().first)
        assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, dispatchedActions.last().second)
    }

    @Test
    fun postConsentRevalidationBlocksGestureOnWindowChangeOrLockOrRevocation() = runBlocking {
        val root = makeNode(pkg = "org.fixture.one", className = "android.widget.LinearLayout", bounds = Rect(0, 0, 1080, 1920))
        val btn = makeNode(pkg = "org.fixture.one", text = "Conferma", clickable = true, bounds = Rect(100, 200, 400, 300))
        attach(root, btn)
        currentRoot = root
        service.captureScreenTree()

        // Case 1: Window package changes while waiting for consent
        val job1 = async(Dispatchers.Default) { phoneUse.tap_screen_text("Conferma") }
        kotlinx.coroutines.withTimeout(5_000) { approvalCoordinator.pendingApproval.first { it != null } }
        val p1 = requireNotNull(approvalCoordinator.pendingApproval.value)
        val otherRoot = makeNode(pkg = "org.fixture.other", className = "android.widget.LinearLayout", bounds = Rect(0, 0, 1080, 1920))
        val otherBtn = makeNode(pkg = "org.fixture.other", text = "Conferma", clickable = true, bounds = Rect(100, 200, 400, 300))
        attach(otherRoot, otherBtn)
        currentRoot = otherRoot
        approvalCoordinator.resolve(p1.id, approved = true)
        val out1 = job1.await()
        assertTrue("Expected window change rejection, got: $out1", out1.contains("Finestra cambiata", ignoreCase = true))
        assertTrue(dispatchedActions.isEmpty())

        // Case 2: Re-read then service disconnects before approval completes
        currentRoot = root
        service.captureScreenTree()
        val job2 = async(Dispatchers.Default) { phoneUse.tap_screen_text("Conferma") }
        kotlinx.coroutines.withTimeout(5_000) { approvalCoordinator.pendingApproval.first { it != null } }
        val p2 = requireNotNull(approvalCoordinator.pendingApproval.value)
        MobHarnAccessibilityService.setTestInstance(null)
        approvalCoordinator.resolve(p2.id, approved = true)
        val out2 = job2.await()
        assertTrue("Expected disconnect/lock failure, got: $out2", out2.contains("scollegato", ignoreCase = true) || out2.contains("bloccato", ignoreCase = true))
        assertTrue(dispatchedActions.isEmpty())

        // Case 3: Even when approval prompts are disabled (remembered/bypassed), revalidation still blocks target mutation!
        MobHarnAccessibilityService.setTestInstance(service)
        monitor.requireApprovalForExternal = false
        currentRoot = root
        service.captureScreenTree()
        val prepared = service.prepare("text", "Conferma")
        assertNotNull(prepared)
        btn.text = "Elimina tutto"
        val err = runCatching { service.applyTarget(prepared) }.exceptionOrNull()
        assertNotNull("Mutated target must be rejected even without interactive prompt", err)
        assertTrue(dispatchedActions.isEmpty())
    }
}
