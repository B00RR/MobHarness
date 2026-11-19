package com.example.data.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.MobHarnRepository
import com.example.data.db.MobHarnDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownVaultManagerTest {

    private lateinit var context: Context
    private lateinit var db: MobHarnDatabase
    private lateinit var vault: MarkdownVaultManager
    private lateinit var repo: MobHarnRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MobHarnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = MarkdownVaultManager(context)
        val workspace = WorkspaceManager(context)
        val prefs = PreferencesRepository(context)
        repo = MobHarnRepository(context, db, vault, workspace, prefs)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun editParagraph_addUnrelatedFact_reopen_preservesUserEdits() = runBlocking {
        // Step 1: Ensure initial vault state
        vault.ensureInitialized(emptyList(), emptyList())

        // Step 2: User opens editor and writes a custom paragraph in USER.md
        val customUserParagraph = "Preferisco risposte tecniche dettagliate, niente convenevoli e sintassi Kotlin idiomatica."
        repo.saveUserMd("# Profilo Utente\n\n$customUserParagraph\n")

        // Step 3: Add an unrelated structured memory (category FACT belongs to MEMORY.md, not USER.md)
        repo.upsertMemoryAndSync(
            category = "FACT",
            keyName = "pixel_ram",
            content = "16 GB LPDDR5X",
            importance = 4
        )

        // Step 4: Reopen the vault (simulate editor reload)
        val reopened = repo.readVault()

        // Step 5: Verify user-written paragraph in USER.md was NOT erased
        assertTrue(
            "User's manual paragraph in USER.md must be preserved after syncing unrelated fact",
            reopened.userMd.contains(customUserParagraph)
        )

        // Step 6: Verify the unrelated fact was synced to MEMORY.md
        assertTrue(
            "Unrelated fact should be present in MEMORY.md",
            reopened.memoryMd.contains("pixel_ram") && reopened.memoryMd.contains("16 GB LPDDR5X")
        )
    }

    @Test
    fun editMemoryMd_addUserProfile_reopen_preservesMemoryEdits() = runBlocking {
        vault.ensureInitialized(emptyList(), emptyList())

        // User edits MEMORY.md directly in the vault editor
        val customMemoryNote = "Regola del team: tutti i modelli devono supportare la quantizzazione int4."
        repo.saveMemoryMd("# Convenzioni MobHarn\n\n$customMemoryNote\n")

        // Add a memory belonging to USER_PROFILE (belongs to USER.md)
        repo.upsertMemoryAndSync(
            category = "USER_PROFILE",
            keyName = "username",
            content = "Lorenzo",
            importance = 5
        )

        val reopened = repo.readVault()

        // Verify MEMORY.md edits were preserved
        assertTrue(
            "User's manual notes in MEMORY.md must be preserved after syncing user profile",
            reopened.memoryMd.contains(customMemoryNote)
        )

        // Verify USER.md received the new structured preference
        assertTrue(
            "USER.md must contain the new username entry",
            reopened.userMd.contains("username") && reopened.userMd.contains("Lorenzo")
        )
    }

    @Test
    fun unmarkedText_isTreatedAsUserContentAndPreservedAcrossMultipleSyncs() = runBlocking {
        // Write completely unmarked text without any HTML comments or delimiters
        val unmarkedUserNotes = "# Note Libere Senza Marker\n\nPrima riga di note personali.\nSeconda riga importante."
        vault.writeUserMd(unmarkedUserNotes)

        // First memory sync
        repo.upsertMemoryAndSync("PREFERENCE", "theme", "dark", 3)
        var docs = repo.readVault()

        assertTrue(docs.userMd.contains("Prima riga di note personali."))
        assertTrue(docs.userMd.contains("Seconda riga importante."))
        assertTrue(docs.userMd.contains("theme"))
        assertTrue(docs.userMd.contains(MarkdownVaultManager.GENERATED_SECTION_START))

        // Second memory sync (add another fact)
        repo.upsertMemoryAndSync("PREFERENCE", "language", "it", 5)
        docs = repo.readVault()

        assertTrue(docs.userMd.contains("Prima riga di note personali."))
        assertTrue(docs.userMd.contains("Seconda riga importante."))
        assertTrue(docs.userMd.contains("theme"))
        assertTrue(docs.userMd.contains("language"))
    }

    @Test
    fun deletingMemory_preservesManualVaultEdits() = runBlocking {
        vault.ensureInitialized(emptyList(), emptyList())

        val id1 = repo.upsertMemoryAndSync("FACT", "fact_one", "valore 1", 3)
        val id2 = repo.upsertMemoryAndSync("FACT", "fact_two", "valore 2", 3)

        // User edits MEMORY.md to add a persistent project note
        val projectNote = "Note architettura: microkernel pattern per i plugin."
        val currentMemory = repo.readVault().memoryMd
        repo.saveMemoryMd("$projectNote\n\n$currentMemory")

        // Delete fact_one
        repo.deleteMemoryAndSync(id1)

        val reopened = repo.readVault()

        assertTrue(
            "Project note must survive memory deletion",
            reopened.memoryMd.contains(projectNote)
        )
        assertFalse(
            "fact_one should be deleted from generated section",
            reopened.memoryMd.contains("fact_one")
        )
        assertTrue(
            "fact_two should remain in generated section",
            reopened.memoryMd.contains("fact_two")
        )
    }
}
