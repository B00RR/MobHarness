package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.di.MobHarnAppContainer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class VerifiedToolsTest {
    @org.junit.Before fun initializeScheduler() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }
    @Test fun unknownToolExplainsNextStepInsteadOfDisappearing() {
        val registry = MobHarnAppContainer.getInstance(ApplicationProvider.getApplicationContext<Context>()).toolkitRegistry
        assertTrue(registry.invokeToolByName("missing_example", emptyMap(), emptySet()).orEmpty().contains("non disponibile"))
    }
    @Test fun disabledAliasNeverExecutes() {
        val registry = MobHarnAppContainer.getInstance(ApplicationProvider.getApplicationContext<Context>()).toolkitRegistry
        assertTrue(registry.invokeToolByName("calc", mapOf("expression" to "2+2"), emptySet()).orEmpty().contains("non attivo"))
    }
    @Test fun exampleRequiresExactReturnedValueNotPrintedText() {
        val tester = com.example.agent.tools.DataToolSet
        assertTrue(tester.matchesExample("=> [1,2]", "[1,2]"))
        assertFalse(tester.matchesExample("=> [1,3]", "[1,2]"))
        assertFalse(tester.matchesExample("[1,2]", "[1,2]"))
    }

    @Test fun visibleTargetMustStillMatchPackageAndLabel() {
        val svc = org.robolectric.Robolectric.buildService(com.example.agent.phoneuse.MobHarnAccessibilityService::class.java).get()
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
            packageName = "org.example.one"; text = "Cerca"; isEnabled = true; isVisibleToUser = true
            setBoundsInScreen(android.graphics.Rect(0, 0, 100, 60))
        }
        val target = requireNotNull(svc.identify(node, "text", "Cerca", -1, -1))
        assertNotNull(svc.resolveTarget(node, target))
        node.packageName = "org.example.two"
        assertNull(svc.resolveTarget(node, target))
        node.packageName = "org.example.one"; node.text = "Invia"
        assertNull(svc.resolveTarget(node, target))
        node.isPassword = true
        assertNull(svc.identify(node, "text", "Invia", -1, -1))
    }
}
