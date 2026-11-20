package com.example

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.phoneuse.MobHarnAccessibilityService
import com.example.di.MobHarnAppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class TwoScreenProbe {
    @Test fun navigateAndReadWithoutChangingEitherState() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app = MobHarnAppContainer.getInstance(inst.targetContext)
        val tools = app.toolkitRegistry.phoneUseTools
        val pkg = inst.context.packageName
        suspend fun observed(label: String): String {
            var last = "service not bound"
            val ready = withTimeoutOrNull(12_000) {
            while (true) {
                val tree = runCatching { MobHarnAccessibilityService.instance?.captureScreenTree() }.getOrNull().orEmpty()
                last = tree.ifEmpty { "service not bound" }
                if (tree.startsWith("App in primo piano: $pkg ") && tree.contains("\"$label\"")) break
                delay(120)
            }
            true
            }
            check(ready == true) { "Missing $label; last observation: $last" }
            return tools.read_screen_ui()
        }
        suspend fun tapOnce(label: String) = coroutineScope {
            val action = async(Dispatchers.IO) { tools.tap_screen_text(label) }
            val pending = withTimeout(8_000) { app.approvalCoordinator.pendingApproval.first { it != null } }!!
            assertEquals("tap_screen_text", pending.toolName)
            app.approvalCoordinator.resolve(pending.id, approved = true)
            val out = action.await()
            assertTrue(out, out.contains("inviata", true) || out.contains("verificat", true))
        }
        for (on in listOf(false, true)) {
            app.toolMonitor.beginTurn()
            app.toolMonitor.requireApprovalForExternal = true
            val command = "am start -W -f 0x10008000 -n $pkg/com.example.ScreenFixture --ez enabled $on"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { val out = it.readText(); check(out.contains("Status: ok")) { out } }
            assertTrue(observed("Archivio").contains("Archivio"))
            tapOnce("Archivio")
            assertTrue(observed("Dettagli").contains("Dettagli"))
            tapOnce("Dettagli")
            val page = observed("Avvisi")
            val row = page.lines().single { it.contains("\"Avvisi\"") }
            assertTrue(row, row.contains(if (on) ",checked]" else ",unchecked]"))
            assertTrue(page, page.contains("passi=2; modifiche=0"))
        }
    }
}
