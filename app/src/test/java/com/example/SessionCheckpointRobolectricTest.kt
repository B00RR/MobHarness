package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.vault.MarkdownVaultManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionCheckpointRobolectricTest {

    @Test
    fun checkpointSurvivesReopeningChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault1 = MarkdownVaultManager(context)
        val sessionId = 999L

        val initialSummary = "- Decisione: architettura MVI adottata.\n- File: AppRouter.kt"
        vault1.writeSessionDigest(sessionId, initialSummary, lastSummarizedMessageId = 42L)

        // Read immediately from current instance
        val checkpoint1 = vault1.readSessionCheckpoint(sessionId)
        assertEquals(42L, checkpoint1.lastSummarizedMessageId)
        assertEquals(initialSummary, checkpoint1.digest)
        assertEquals(initialSummary, vault1.readSessionDigest(sessionId))

        // Simulate closing and reopening the chat / app by creating a new manager instance
        val vault2 = MarkdownVaultManager(context)
        val checkpoint2 = vault2.readSessionCheckpoint(sessionId)
        assertEquals(42L, checkpoint2.lastSummarizedMessageId)
        assertEquals(initialSummary, checkpoint2.digest)
        assertFalse(vault2.readSessionDigest(sessionId).contains("checkpoint:"))
    }
}
