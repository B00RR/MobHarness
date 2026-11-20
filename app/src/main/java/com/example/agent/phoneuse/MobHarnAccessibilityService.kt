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
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class MobHarnAccessibilityService : AccessibilityService() {

    internal var testRootProvider: (() -> AccessibilityNodeInfo?)? = null
    internal var testWindowTypeProvider: ((AccessibilityNodeInfo) -> Int?)? = null
    internal var testDeviceLockedProvider: (() -> Boolean)? = null
    internal var testActionPerformer: ((AccessibilityNodeInfo, Int, Bundle?) -> Boolean)? = null
    internal var testPause: (suspend (Long) -> Unit)? = null

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

    private fun isDeviceLockedNow(): Boolean =
        testDeviceLockedProvider?.invoke()
            ?: (getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked != false)

    private fun currentRootNode(): AccessibilityNodeInfo? =
        testRootProvider?.invoke() ?: rootInActiveWindow

    private fun windowTypeOf(root: AccessibilityNodeInfo): Int? =
        testWindowTypeProvider?.invoke(root) ?: root.window?.type

    private fun performNodeAction(node: AccessibilityNodeInfo, action: Int, args: Bundle? = null): Boolean =
        testActionPerformer?.invoke(node, action, args)
            ?: if (args != null) node.performAction(action, args) else node.performAction(action)

    private suspend fun pauseMs(ms: Long) {
        val hook = testPause
        if (hook != null) hook(ms) else kotlinx.coroutines.delay(ms)
    }

    private fun activeApp(): AccessibilityNodeInfo? {
        if (instance !== this || isDeviceLockedNow()) return null
        val root = currentRootNode() ?: return null
        val pkg = root.packageName?.toString() ?: return null
        return root.takeIf {
            pkg != packageName &&
                pkg !in PROTECTED_PACKAGES &&
                windowTypeOf(root) == AccessibilityWindowInfo.TYPE_APPLICATION
        }
    }

    internal fun readCheckedState(node: AccessibilityNodeInfo): Boolean? {
        if (!node.isCheckable) return null
        return if (Build.VERSION.SDK_INT >= 36) {
            when (node.checked) {
                AccessibilityNodeInfo.CHECKED_STATE_TRUE -> true
                AccessibilityNodeInfo.CHECKED_STATE_FALSE -> false
                AccessibilityNodeInfo.CHECKED_STATE_PARTIAL -> null
                else -> {
                    @Suppress("DEPRECATION")
                    node.isChecked
                }
            }
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }
    }

    internal fun contextLabelsFor(node: AccessibilityNodeInfo): List<String> {
        val collected = linkedSetOf<String>()
        var ancestor = node.parent
        var hops = 0
        while (ancestor != null && hops < 3) {
            if (ancestor.isPassword) break
            if (!ancestor.isEditable) {
                ancestor.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { collected.add(it.take(80)) }
                ancestor.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { collected.add(it.take(80)) }
            }
            if (ancestor.isScrollable) break
            val childCount = ancestor.childCount
            if (childCount in 1..8) {
                for (i in 0 until childCount) {
                    val sibling = ancestor.getChild(i) ?: continue
                    if (sibling == node || sibling.isPassword || sibling.isEditable) continue
                    collectSafeSubLabels(sibling, node, collected, maxDepth = 2)
                    if (collected.size >= 8) break
                }
            }
            if (collected.size >= 8) break
            ancestor = ancestor.parent
            hops++
        }
        return collected.toList()
    }

    private fun collectSafeSubLabels(
        current: AccessibilityNodeInfo,
        excluded: AccessibilityNodeInfo,
        out: MutableSet<String>,
        maxDepth: Int
    ) {
        if (current == excluded || current.isPassword || current.isEditable || current.isScrollable || out.size >= 8) return
        current.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it.take(80)) }
        current.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it.take(80)) }
        if (maxDepth <= 0) return
        val limit = current.childCount.coerceAtMost(6)
        for (i in 0 until limit) {
            val child = current.getChild(i) ?: continue
            collectSafeSubLabels(child, excluded, out, maxDepth - 1)
            if (out.size >= 8) return
        }
    }

    fun captureScreenTree(maxNodes: Int = 80): String {
        observed = null
        val root = activeApp() ?: error("Finestra applicativa non disponibile, bloccata o protetta.")
        observed = root.windowKey()
        return renderScreenTree(root, maxNodes)
    }

    internal fun renderScreenTree(root: AccessibilityNodeInfo, maxNodes: Int = 80): String {
        val pkg = root.packageName?.toString() ?: _foregroundPackage.value
        val displayMetrics = resources.displayMetrics
        val lines = mutableListOf<String>()
        lines.add("App in primo piano: $pkg (schermo ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}px)")

        var index = 0
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || index >= maxNodes || depth > 18) return
            if (node.isPassword) return
            val editable = node.isEditable
            val rawText = if (editable) "" else node.text?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val scrollable = node.isScrollable
            val checkable = node.isCheckable

            if (rawText.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || clickable || editable || checkable || scrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    val flags = buildList {
                        if (clickable) add("tap")
                        if (editable) {
                            add("input")
                            val hasPrivateText = !node.text.isNullOrEmpty()
                            add(if (hasPrivateText) "filled" else "empty")
                        }
                        if (scrollable) add("scroll")
                        if (checkable) {
                            add("checkable")
                            when (readCheckedState(node)) {
                                true -> add("checked")
                                false -> add("unchecked")
                                null -> add("partial")
                            }
                        }
                    }.joinToString(",")

                    val ownLabel = when {
                        editable -> when {
                            hint.isNotEmpty() && desc.isNotEmpty() && hint != desc -> "\"${hint.take(60)}\" (${desc.take(40)})"
                            hint.isNotEmpty() -> "\"${hint.take(60)}\""
                            desc.isNotEmpty() -> "(${desc.take(60)})"
                            else -> ""
                        }
                        rawText.isNotEmpty() && desc.isNotEmpty() && rawText != desc -> "\"${rawText.take(70)}\" (${desc.take(40)})"
                        rawText.isNotEmpty() -> "\"${rawText.take(80)}\""
                        desc.isNotEmpty() -> "(${desc.take(60)})"
                        else -> ""
                    }
                    val rowContext = if ((checkable || editable) && ownLabel.isEmpty()) {
                        contextLabelsFor(node).firstOrNull()?.let { "in \"${it.take(60)}\"" }.orEmpty()
                    } else if (checkable) {
                        contextLabelsFor(node)
                            .firstOrNull { !it.equals(rawText, true) && !it.equals(desc, true) }
                            ?.let { "in \"${it.take(60)}\"" }.orEmpty()
                    } else {
                        ""
                    }
                    val label = listOf(ownLabel, rowContext).filter { it.isNotEmpty() }.joinToString(" ")
                    if (label.isNotEmpty() || editable || checkable || scrollable) {
                        index++
                        val flagPart = if (flags.isNotEmpty()) " [$flags]" else ""
                        val labelPart = if (label.isNotEmpty()) " $label" else ""
                        lines.add("#$index $role$labelPart$flagPart @($cx,$cy)")
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

    internal enum class FailureKind {
        SERVICE_UNAVAILABLE,
        WINDOW_UNAVAILABLE,
        SCREEN_NOT_READ,
        WINDOW_CHANGED,
        TARGET_NOT_FOUND,
        TARGET_AMBIGUOUS,
        INVALID_ARGUMENT
    }

    internal sealed interface TargetResolution {
        data class Unique(val target: Target) : TargetResolution
        data class Invalid(val reason: String, val kind: FailureKind) : TargetResolution
    }

    internal data class Target(
        val window: WindowKey,
        val node: AccessibilityNodeInfo,
        val label: String,
        val bounds: Rect,
        val mode: String,
        val query: String,
        val contextHint: String = "",
        val x: Int = -1,
        val y: Int = -1,
        val actionValue: String = ""
    )

    private fun nodeOwnLabels(n: AccessibilityNodeInfo, includeEditableText: Boolean = true): List<String> {
        if (n.isPassword) return emptyList()
        return listOfNotNull(
            if (includeEditableText || !n.isEditable) n.text else null,
            n.contentDescription,
            if (Build.VERSION.SDK_INT >= 26) n.hintText else null,
            n.viewIdResourceName
        ).map { it.toString().trim() }.filter { it.isNotEmpty() }
    }

    private fun matchesContext(node: AccessibilityNodeInfo, contextHint: String, allowDescendants: Boolean = false): Boolean {
        val hint = contextHint.trim()
        if (hint.isBlank()) return true
        val ownAndAncestors = nodeOwnLabels(node, includeEditableText = false) + contextLabelsFor(node)
        if (ownAndAncestors.any { it.equals(hint, ignoreCase = true) || it.contains(hint, ignoreCase = true) }) {
            return true
        }
        if (allowDescendants) {
            val descLabels = linkedSetOf<String>()
            val limit = node.childCount.coerceAtMost(8)
            for (i in 0 until limit) {
                val child = node.getChild(i) ?: continue
                collectSafeSubLabels(child, node, descLabels, maxDepth = 2)
            }
            if (descLabels.any { it.equals(hint, ignoreCase = true) || it.contains(hint, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    private fun clickableAncestorOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var click: AccessibilityNodeInfo? = node
        while (click != null && !click.isClickable) click = click.parent
        return click
    }

    private fun scrollActionFor(direction: String): AccessibilityNodeInfo.AccessibilityAction? =
        when (direction.lowercase().trim()) {
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> null
        }

    private fun candidates(
        root: AccessibilityNodeInfo,
        mode: String,
        q: String,
        contextHint: String = "",
        x: Int = -1,
        y: Int = -1,
        actionValue: String = ""
    ): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var budget = 600
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (--budget < 0 || depth > 24 || n.isPassword) return
            val rect = Rect().also { n.getBoundsInScreen(it) }
            val labels = nodeOwnLabels(n, includeEditableText = mode == "input")
            val hit = when (mode) {
                "text" -> q.isNotBlank() &&
                    labels.any { it.equals(q, true) } &&
                    matchesContext(n, contextHint)
                "point" -> rect.centerX() == x && rect.centerY() == y &&
                    (labels.any { it.isNotBlank() } || n.isCheckable || n.isEditable) &&
                    matchesContext(n, contextHint)
                "input" -> n.isEditable && (
                    if (q.isBlank()) {
                        n.isFocused && matchesContext(n, contextHint)
                    } else {
                        (labels.any { it.equals(q, true) } ||
                            contextLabelsFor(n).any { it.equals(q, true) }) &&
                            matchesContext(n, contextHint)
                    }
                )
                "scroll" -> n.isScrollable && matchesContext(n, contextHint, allowDescendants = true)
                else -> false
            }
            if (hit && n.isEnabled && n.isVisibleToUser && !rect.isEmpty) matches.add(n)
            for (i in 0 until n.childCount) {
                if (budget <= 0) break
                n.getChild(i)?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        if (budget <= 0) return emptyList()

        // Collapse ancestor/descendant duplicates that resolve to the exact same clickable container,
        // without ever merging distinct sibling targets.
        if (mode == "text" && matches.size > 1) {
            val collapsed = mutableListOf<AccessibilityNodeInfo>()
            val seenClickables = mutableSetOf<AccessibilityNodeInfo>()
            for (m in matches) {
                val clickTarget = clickableAncestorOf(m)
                if (clickTarget != null && matches.any { it != m && it == clickTarget }) {
                    // The clickable container itself is already in matches; skip redundant inner duplicate.
                    continue
                }
                if (clickTarget != null && !seenClickables.add(clickTarget)) {
                    continue
                }
                collapsed.add(m)
            }
            return collapsed
        }

        if (mode == "scroll" && matches.size > 1) {
            val dirAction = scrollActionFor(actionValue)
            if (dirAction != null) {
                val directional = matches.filter { node ->
                    node.actionList.any { it.id == dirAction.id }
                }
                if (directional.isNotEmpty()) return directional
            }
        }

        return matches
    }

    private fun signature(n: AccessibilityNodeInfo): String {
        val safeText = if (n.isEditable || n.isPassword) "" else n.text
        return listOf(n.className, n.viewIdResourceName, safeText, n.contentDescription, if (Build.VERSION.SDK_INT >= 26) n.hintText else null)
            .joinToString("\u0000")
    }

    internal fun prepareTarget(
        mode: String,
        query: String = "",
        contextHint: String = "",
        x: Int = -1,
        y: Int = -1,
        actionValue: String = ""
    ): TargetResolution {
        if (mode == "scroll" && scrollActionFor(actionValue) == null) {
            return TargetResolution.Invalid(
                "Direzione di scorrimento non valida: '$actionValue'. Usa down, up, left o right.",
                FailureKind.INVALID_ARGUMENT
            )
        }
        if (mode == "text" && query.isBlank()) {
            return TargetResolution.Invalid(
                "Etichetta bersaglio vuota. Specifica un testo visibile sullo schermo.",
                FailureKind.INVALID_ARGUMENT
            )
        }
        if (mode == "point" && (x < 0 || y < 0)) {
            return TargetResolution.Invalid(
                "Coordinate non valide ($x, $y). Rileggi lo schermo prima di agire.",
                FailureKind.INVALID_ARGUMENT
            )
        }
        if (instance !== this) {
            return TargetResolution.Invalid(
                "Servizio Accessibilità non connesso.",
                FailureKind.SERVICE_UNAVAILABLE
            )
        }
        if (isDeviceLockedNow()) {
            return TargetResolution.Invalid(
                "Telefono bloccato. Nessuna azione consentita.",
                FailureKind.WINDOW_UNAVAILABLE
            )
        }
        // One bounded re-read is allowed if the active root transiently returned null; never fall back to blind coords.
        val root = activeApp() ?: activeApp()
            ?: return TargetResolution.Invalid(
                "Finestra applicativa non disponibile, bloccata o protetta.",
                FailureKind.WINDOW_UNAVAILABLE
            )
        val lastObserved = observed
            ?: return TargetResolution.Invalid(
                "Nessuna lettura recente dello schermo. Chiama prima read_screen_ui.",
                FailureKind.SCREEN_NOT_READ
            )
        if (root.windowKey() != lastObserved) {
            return TargetResolution.Invalid(
                "Finestra cambiata rispetto all'ultima lettura. Rileggi lo schermo prima di agire.",
                FailureKind.WINDOW_CHANGED
            )
        }
        val cleanQuery = query.trim()
        val cleanContext = contextHint.trim()
        val matches = candidates(root, mode, cleanQuery, cleanContext, x, y, actionValue)
        return when (matches.size) {
            0 -> TargetResolution.Invalid(
                "Bersaglio non trovato sulla schermata letta ($mode${if (cleanQuery.isNotBlank()) " '$cleanQuery'" else ""}${if (cleanContext.isNotBlank()) " in '$cleanContext'" else ""}).",
                FailureKind.TARGET_NOT_FOUND
            )
            1 -> {
                val n = matches.single()
                TargetResolution.Unique(
                    Target(
                        window = root.windowKey(),
                        node = n,
                        label = signature(n),
                        bounds = Rect().also { n.getBoundsInScreen(it) },
                        mode = mode,
                        query = cleanQuery,
                        contextHint = cleanContext,
                        x = x,
                        y = y,
                        actionValue = actionValue.trim()
                    )
                )
            }
            else -> {
                val hints = matches.mapNotNull { m ->
                    contextLabelsFor(m).firstOrNull { !it.equals(cleanQuery, true) }
                }.distinct().take(3)
                val hintSuffix = if (hints.isNotEmpty()) {
                    " Contesti rilevati: ${hints.joinToString(", ")}."
                } else ""
                TargetResolution.Invalid(
                    "Bersaglio ambiguo: trovate ${matches.size} corrispondenze.$hintSuffix Specifica il contesto o un'etichetta univoca.",
                    FailureKind.TARGET_AMBIGUOUS
                )
            }
        }
    }

    internal fun prepare(mode: String, query: String = "", x: Int = -1, y: Int = -1, contextHint: String = "", actionValue: String = ""): Target? =
        (prepareTarget(mode, query, contextHint, x, y, actionValue) as? TargetResolution.Unique)?.target

    internal fun identify(
        root: AccessibilityNodeInfo,
        mode: String,
        query: String,
        x: Int,
        y: Int,
        contextHint: String = ""
    ): Target? {
        val n = candidates(root, mode, query.trim(), contextHint.trim(), x, y).singleOrNull() ?: return null
        return Target(root.windowKey(), n, signature(n), Rect().also { n.getBoundsInScreen(it) }, mode, query.trim(), contextHint.trim(), x, y)
    }

    internal suspend fun applyTarget(target: Target?, value: String = ""): String {
        val t = target ?: error("Nessun bersaglio univoco sulla schermata letta. Rileggi prima di agire.")
        val root = withTimeoutOrNull(3200L) {
            var r = activeApp()
            while (r == null) {
                check(instance === this@MobHarnAccessibilityService && !isDeviceLockedNow()) {
                    "Telefono bloccato o servizio scollegato. Nessuna azione."
                }
                pauseMs(120)
                r = activeApp()
            }
            check(r.windowKey() == t.window) { "Finestra cambiata. Nessuna azione." }
            r
        } ?: error("Finestra autorizzata non visibile. Nessuna azione. Chiudi le notifiche e rileggi la schermata.")
        val n = requireNotNull(resolveTarget(root, t)) { "Il bersaglio è cambiato. Nessuna azione." }
        check(activeApp()?.windowKey() == t.window) { "Finestra cambiata." }
        observed = null // Require a fresh read before the next action.
        when (t.mode) {
            "input" -> {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                check(performNodeAction(n, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "Digitazione rifiutata." }
            }
            "scroll" -> {
                val action = scrollActionFor(value) ?: error("Direzione non valida.")
                check(performNodeAction(n, action.id)) { "Scorrimento rifiutato." }
            }
            else -> {
                val rect = Rect().also { n.getBoundsInScreen(it) }
                check(t.mode != "point" || t.bounds == rect) { "Coordinate cambiate: rileggi la schermata." }
                val click = clickableAncestorOf(n)
                check(click != null && click.isVisibleToUser && click.isEnabled && performNodeAction(click, AccessibilityNodeInfo.ACTION_CLICK)) {
                    "Tap non accettato. Nessun tocco alla cieca."
                }
                if (testActionPerformer == null) {
                    showTap(rect.centerX(), rect.centerY())
                }
            }
        }
        return "Azione inviata all'app autorizzata. Rileggi lo schermo per verificarne l'effetto."
    }

    internal fun resolveTarget(root: AccessibilityNodeInfo, t: Target): AccessibilityNodeInfo? {
        if (root.windowKey() != t.window) return null
        return candidates(root, t.mode, t.query, t.contextHint, t.x, t.y, t.actionValue).singleOrNull()?.takeIf {
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
            val lp = android.view.WindowManager.LayoutParams(
                size, size, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
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
            if (node.isEditable && !node.isPassword) {
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
            if (node.isEditable && !node.isPassword) {
                match = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return match
    }

    companion object {
        private val PROTECTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

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
