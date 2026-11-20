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
    @Test fun unknownToolExplainsNextStepInsteadOfDisappearing() {
        val registry = MobHarnAppContainer.getInstance(ApplicationProvider.getApplicationContext<Context>()).toolkitRegistry
        assertTrue(registry.invokeToolByName("missing_example", emptyMap(), emptySet()).orEmpty().contains("non disponibile"))
    }
    @Test fun disabledAliasNeverExecutes() {
        val registry = MobHarnAppContainer.getInstance(ApplicationProvider.getApplicationContext<Context>()).toolkitRegistry
        assertTrue(registry.invokeToolByName("calc", mapOf("expression" to "2+2"), emptySet()).orEmpty().contains("non attivo"))
    }
}
