package com.example.agent.phoneuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
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

    fun captureScreenTree(maxNodes: Int = 80): String {
        val root = rootInActiveWindow
            ?: return "Finestra attiva non disponibile (schermo spento o protetto)."
        val pkg = root.packageName?.toString() ?: _foregroundPackage.value
        val displayMetrics = resources.displayMetrics
        val lines = mutableListOf<String>()
        lines.add("App in primo piano: $pkg (schermo ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}px)")

        var index = 0
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || index >= maxNodes || depth > 18) return
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

    suspend fun tapByText(query: String): String {
        val root = rootInActiveWindow ?: return "Nessuna finestra attiva accessibile."
        val target = findNodeByQuery(root, query.trim())
            ?: return "Elemento '$query' non trovato sullo schermo attuale."

        // Try standard accessibility click on node or clickable ancestor
        var clickableNode: AccessibilityNodeInfo? = target
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "Tap eseguito su '$query'."
        }

        // Fallback to gesture tap at center of bounding box
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val ok = performTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
            return if (ok) "Gesture tap eseguito su '$query' @(${rect.centerX()},${rect.centerY()})."
            else "Impossibile eseguire il tap su '$query'."
        }
        return "Elemento '$query' trovato ma non cliccabile."
    }

    suspend fun tapCoordinates(x: Float, y: Float): String {
        val ok = performTapGesture(x, y)
        return if (ok) "Tap eseguito alle coordinate (${x.toInt()}, ${y.toInt()})."
        else "Gesture tap fallito alle coordinate (${x.toInt()}, ${y.toInt()})."
    }

    fun typeText(fieldHint: String, textToType: String): String {
        val root = rootInActiveWindow ?: return "Nessuna finestra attiva accessibile."
        return typeTextInternal(root, fieldHint, textToType)
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

    suspend fun scrollScreen(direction: String): String {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val path = Path()
        when (direction.lowercase().trim()) {
            "up" -> {
                path.moveTo(cx, dm.heightPixels * 0.28f)
                path.lineTo(cx, dm.heightPixels * 0.76f)
            }
            "left" -> {
                path.moveTo(dm.widthPixels * 0.8f, cy)
                path.lineTo(dm.widthPixels * 0.2f, cy)
            }
            "right" -> {
                path.moveTo(dm.widthPixels * 0.2f, cy)
                path.lineTo(dm.widthPixels * 0.8f, cy)
            }
            else -> { // down
                path.moveTo(cx, dm.heightPixels * 0.76f)
                path.lineTo(cx, dm.heightPixels * 0.28f)
            }
        }
        val ok = dispatchPathGesture(path, durationMs = 280L)
        return if (ok) "Scorrimento '$direction' eseguito."
        else "Scorrimento '$direction' non riuscito."
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

    private suspend fun performTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }
        return dispatchPathGesture(path, durationMs = 60L)
    }

    private suspend fun dispatchPathGesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()
        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!deferred.isCompleted) deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (!deferred.isCompleted) deferred.complete(false)
                }
            },
            null
        )
        if (!started) return false
        return withTimeoutOrNull(2500L) { deferred.await() } ?: false
    }

    private fun findNodeByQuery(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val direct = root.findAccessibilityNodeInfosByText(query)
        if (!direct.isNullOrEmpty()) return direct.first()

        var match: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || match != null) return
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true)) {
                match = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return match
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
