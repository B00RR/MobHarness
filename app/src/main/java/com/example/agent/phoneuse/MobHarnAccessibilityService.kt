package com.example.agent.phoneuse

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class MobHarnAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _foregroundPackage.value = pkg
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
    }

    internal data class WindowKey(val pkg: String, val id: Int)
    @Volatile private var observed: WindowKey? = null
    private fun AccessibilityNodeInfo.windowKey() = WindowKey(packageName?.toString().orEmpty(), windowId)
    private fun activeApp(): AccessibilityNodeInfo? {
        if (instance !== this || getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked != false) return null
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        return root.takeIf { pkg != packageName && pkg !in setOf("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller") && root.window?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
    }

    fun captureScreenTree(maxNodes: Int = 80): String {
        observed = null
        val root = activeApp() ?: error("Finestra applicativa non disponibile, bloccata o protetta.")
        observed = root.windowKey()
        val pkg = root.packageName?.toString() ?: _foregroundPackage.value
        val displayMetrics = resources.displayMetrics
        val lines = mutableListOf<String>()
        lines.add("App in primo piano: $pkg (schermo ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}px)")

        var index = 0
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || index >= maxNodes || depth > 18) return
            if (node.isPassword) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable

            if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    val flags = buildList {
                        if (clickable) add("tap")
                        if (editable) add("input")
                        if (scrollable) add("scroll")
                        if (node.isCheckable) {
                            val checkedNow = if (Build.VERSION.SDK_INT >= 36) {
                                node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
                            } else {
                                @Suppress("DEPRECATION")
                                node.isChecked
                            }
                            add(if (checkedNow) "checked" else "unchecked")
                        }
                    }.joinToString(",")
                    val label = when {
                        text.isNotEmpty() && desc.isNotEmpty() && text != desc -> "\"${text.take(70)}\" (${desc.take(40)})"
                        text.isNotEmpty() -> "\"${text.take(80)}\""
                        desc.isNotEmpty() -> "(${desc.take(60)})"
                        else -> ""
                    }
                    if (label.isNotEmpty() || editable) {
                        index++
                        val flagPart = if (flags.isNotEmpty()) " [$flags]" else ""
                        lines.add("#$index $role $label$flagPart @($cx,$cy)")
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)
        return if (lines.size == 1) {
            lines.first() + "\n(Nessun elemento testuale/interattivo visibile)"
        } else {
            lines.joinToString("\n")
        }
    }

    internal data class Target(val window: WindowKey, val node: AccessibilityNodeInfo, val label: String,
        val bounds: Rect, val mode: String, val query: String, val x: Int, val y: Int)

    private fun candidates(root: AccessibilityNodeInfo, mode: String, q: String, x: Int, y: Int): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var budget = 600
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (--budget < 0 || depth > 24 || n.isPassword) return
            val rect = Rect().also { n.getBoundsInScreen(it) }
            val labels = listOfNotNull(n.text, n.contentDescription, if (Build.VERSION.SDK_INT >= 26) n.hintText else null, n.viewIdResourceName).map { it.toString() }
            val hit = when (mode) {
                "text" -> q.isNotBlank() && labels.any { it.equals(q, true) }
                "point" -> rect.centerX() == x && rect.centerY() == y && labels.any { it.isNotBlank() }
                "input" -> n.isEditable && (if (q.isBlank()) n.isFocused else labels.any { it.equals(q, true) })
                "scroll" -> n.isScrollable
                else -> false
            }
            if (hit && n.isEnabled && n.isVisibleToUser && !rect.isEmpty) matches.add(n)
            for (i in 0 until n.childCount) { if (budget <= 0) break; n.getChild(i)?.let { visit(it, depth + 1) } }
        }
        visit(root, 0)
        return if (budget <= 0) emptyList() else matches
    }

    private fun signature(n: AccessibilityNodeInfo) = listOf(n.className, n.viewIdResourceName, n.text, n.contentDescription).joinToString("\u0000")
    internal fun prepare(mode: String, query: String = "", x: Int = -1, y: Int = -1): Target? {
        val root = activeApp() ?: return null
        if (root.windowKey() != observed) return null
        return identify(root, mode, query, x, y)
    }

    internal fun identify(root: AccessibilityNodeInfo, mode: String, query: String, x: Int, y: Int): Target? {
        val n = candidates(root, mode, query.trim(), x, y).singleOrNull() ?: return null
        return Target(root.windowKey(), n, signature(n), Rect().also { n.getBoundsInScreen(it) }, mode, query.trim(), x, y)
    }

    internal suspend fun applyTarget(target: Target?, value: String = ""): String {
        val t = target ?: error("Nessun bersaglio univoco sulla schermata letta. Rileggi prima di agire.")
        val root = withTimeoutOrNull(3200L) {
            var r = activeApp()
            while (r?.windowKey() != t.window) { kotlinx.coroutines.delay(120); r = activeApp() }
            r
        } ?: error("La finestra autorizzata non è tornata in primo piano. Nessuna azione.")
        val n = requireNotNull(resolveTarget(root, t)) { "Il bersaglio è cambiato. Nessuna azione." }
        check(activeApp()?.windowKey() == t.window) { "Finestra cambiata." }
        observed = null // Require a fresh read before the next action.
        when (t.mode) {
            "input" -> check(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) { "Digitazione rifiutata." }
            "scroll" -> {
                val action = when (value.lowercase().trim()) {
                    "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                    "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                    "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                    "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                    else -> error("Direzione non valida.")
                }
                check(n.performAction(action.id)) { "Scorrimento rifiutato." }
            }
            else -> {
                val rect = Rect().also { n.getBoundsInScreen(it) }
                check(t.mode != "point" || t.bounds == rect) { "Coordinate cambiate: rileggi la schermata." }
                var click: AccessibilityNodeInfo? = n
                while (click != null && !click.isClickable) click = click.parent
                check(click != null && click.isVisibleToUser && click.isEnabled && click.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Tap non accettato. Nessun tocco alla cieca." }
                showTap(rect.centerX(), rect.centerY())
            }
        }
        return "Azione inviata all'app autorizzata. Rileggi lo schermo per verificarne l'effetto."
    }

    internal fun resolveTarget(root: AccessibilityNodeInfo, t: Target): AccessibilityNodeInfo? {
        if (root.windowKey() != t.window) return null
        return candidates(root, t.mode, t.query, t.x, t.y).singleOrNull()?.takeIf {
            it == t.node && signature(it) == t.label
        }
    }

    private fun showTap(x: Int, y: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (instance !== this) return@post
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val size = (32 * resources.displayMetrics.density).toInt()
            val ring = android.view.View(this).apply {
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x3355CCFF); setStroke(4, 0xFF00AADD.toInt())
                }
            }
            val lp = android.view.WindowManager.LayoutParams(size, size, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                    this.x = x - size / 2; this.y = y - size / 2
                }
            if (runCatching { wm.addView(ring, lp) }.isSuccess) ring.postDelayed({ runCatching { wm.removeView(ring) } }, 450)
        }
    }

    internal fun typeTextInternal(
        root: AccessibilityNodeInfo,
        fieldHint: String,
        textToType: String,
        actionPerformer: ((AccessibilityNodeInfo, Int, Bundle?) -> Boolean)? = null,
        focusedNodeResolver: ((AccessibilityNodeInfo) -> AccessibilityNodeInfo?)? = null
    ): String {
        val target: AccessibilityNodeInfo = if (fieldHint.isNotBlank()) {
            findEditableByQuery(root, fieldHint.trim())
                ?: return "Campo di testo '$fieldHint' non trovato sullo schermo."
        } else {
            val focused = focusedNodeResolver?.invoke(root)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused ?: findFirstEditable(root)
                ?: return "Nessun campo di testo modificabile trovato sullo schermo."
        }

        if (actionPerformer != null) {
            actionPerformer(target, AccessibilityNodeInfo.ACTION_FOCUS, null)
        } else {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        val ok = if (actionPerformer != null) {
            actionPerformer(target, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return if (ok) {
            val fieldDesc = if (fieldHint.isNotBlank()) "'$fieldHint'" else "attivo"
            "Testo inserito nel campo $fieldDesc."
        } else {
            "Impossibile scrivere nel campo selezionato."
        }
    }

    private val recentsRoutine = RecentsRoutine()

    suspend fun openVerifiedRecents(stillAllowed: () -> Boolean = { true }): String = recentsRoutine.run(
        connected = { instance === this && isConnected.value && stillAllowed() },
        open = { performGlobalAction(GLOBAL_ACTION_RECENTS) },
        visible = {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            // Only public launcher markers. Do not inspect labels, screenshots or individual cards.
            pkg in setOf("com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.android.launcher3.lineage") &&
                root?.findAccessibilityNodeInfosByViewId("$pkg:id/overview_panel")?.any { it.isVisibleToUser } == true
        }
    )

    fun performSystemNavigation(actionName: String): String {
        val action = when (actionName.lowercase().trim()) {
            "back", "indietro" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recenti" -> GLOBAL_ACTION_RECENTS
            "notifications", "notifiche" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (action == -1) return "Azione globale non supportata: '$actionName'."
        val ok = performGlobalAction(action)
        return if (ok) "Azione di sistema '$actionName' eseguita."
        else "Impossibile eseguire l'azione '$actionName'."
    }

    internal fun findEditableByQuery(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        var match: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || match != null) return
            if (node.isEditable) {
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val viewId = node.viewIdResourceName?.toString().orEmpty()
                if (hint.contains(query, true) || text.contains(query, true) || desc.contains(query, true) || viewId.contains(query, true)) {
                    match = node
                    return
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return match
    }

    internal fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var match: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || match != null) return
            if (node.isEditable) {
                match = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return match
    }

    companion object {
        @Volatile
        var instance: MobHarnAccessibilityService? = null
            private set

        internal fun setTestInstance(testService: MobHarnAccessibilityService?) {
            instance = testService
        }

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _foregroundPackage = MutableStateFlow("")
        val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        fun openAppDetailsSettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
