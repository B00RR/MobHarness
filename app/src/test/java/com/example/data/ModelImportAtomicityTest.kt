package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.MobHarnDatabase
import com.example.data.db.ModelProfileEntity
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import com.example.data.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelImportAtomicityTest {

    private lateinit var context: Context
    private lateinit var db: MobHarnDatabase
    private lateinit var repo: MobHarnRepository
    private lateinit var modelsDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MobHarnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vault = MarkdownVaultManager(context)
        val workspace = WorkspaceManager(context)
        val prefs = PreferencesRepository(context)
        repo = MobHarnRepository(context, db, vault, workspace, prefs)

        modelsDir = File(context.filesDir, "litertlm_models").apply { mkdirs() }
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        db.close()
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun interruptedSameNameImport_preservesOldModelAndCleansUpTempFiles() = runBlocking {
        val modelFileName = "gemma4_pixel.litertlm"
        val destFile = File(modelsDir, modelFileName)
        val originalBytes = "ORIGINAL_WORKING_MODEL_BYTES_V1".toByteArray(Charsets.UTF_8)
        destFile.writeBytes(originalBytes)

        // Seed DB with the existing working model
        val existingProfile = ModelProfileEntity(
            displayName = "gemma4 pixel",
            filePath = destFile.absolutePath,
            fileSizeBytes = originalBytes.size.toLong(),
            supportsVision = false,
            preferredBackend = "GPU",
            maxNumTokens = 6144,
            temperature = 0.5f, // custom user preference
            topK = 32,
            topP = 0.9f,
            thinkingEnabled = true
        )
        val seededId = db.modelProfileDao().upsertModel(existingProfile)

        // Simulate an interrupted reimport: throws IOException midway through reading
        val partialBytes = "CORRUPTED_INCOMPLETE_NEW_BYTES_HALFWAY".toByteArray(Charsets.UTF_8)
        val interruptedStreamProvider: () -> InputStream = {
            object : InputStream() {
                private var pos = 0
                override fun read(): Int {
                    if (pos < partialBytes.size) {
                        return partialBytes[pos++].toInt() and 0xFF
                    }
                    throw IOException("Simulated storage full / stream interrupted halfway")
                }
            }
        }

        val result = repo.importModelFromStream(
            displayName = modelFileName,
            totalBytes = 50_000L,
            openStream = interruptedStreamProvider
        )

        // 1. Verify import returned failure
        assertTrue("Import must fail when stream is interrupted", result.isFailure)

        // 2. Verify destination file still exists and contains the EXACT original bytes
        assertTrue("Existing model file must still exist", destFile.exists())
        assertEquals(
            "Working copy bytes must not be corrupted or truncated",
            "ORIGINAL_WORKING_MODEL_BYTES_V1",
            destFile.readText(Charsets.UTF_8)
        )
        assertEquals(originalBytes.size.toLong(), destFile.length())

        // 3. Verify failed temporary files were cleaned up
        val remainingTmpFiles = modelsDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertEquals("Temporary files must be deleted on failure", 0, remainingTmpFiles.size)

        // 4. Verify existing profile in DB remains intact
        val profileInDb = db.modelProfileDao().getModelById(seededId)
        assertEquals("gemma4 pixel", profileInDb?.displayName)
        assertEquals(originalBytes.size.toLong(), profileInDb?.fileSizeBytes)
        assertEquals(0.5f, profileInDb?.temperature)
    }

    @Test
    fun sizeMismatch_failsAndCleansUpWithoutModifyingExistingModel() = runBlocking {
        val modelFileName = "qwen_edge.litertlm"
        val destFile = File(modelsDir, modelFileName)
        val originalBytes = "QWEN_MODEL_ORIGINAL_BYTES".toByteArray(Charsets.UTF_8)
        destFile.writeBytes(originalBytes)

        // Stream supplies 20 bytes but header claims 5000 bytes
        val incompleteBytes = "INCOMPLETE_DATA".toByteArray(Charsets.UTF_8)
        val result = repo.importModelFromStream(
            displayName = modelFileName,
            totalBytes = 5000L,
            openStream = { ByteArrayInputStream(incompleteBytes) }
        )

        assertTrue(result.isFailure)
        assertTrue(destFile.exists())
        assertEquals("QWEN_MODEL_ORIGINAL_BYTES", destFile.readText(Charsets.UTF_8))

        val remainingTmpFiles = modelsDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertEquals(0, remainingTmpFiles.size)
    }

    @Test
    fun emptyStream_failsValidationAndPreservesExistingModel() = runBlocking {
        val modelFileName = "phi_model.litertlm"
        val destFile = File(modelsDir, modelFileName)
        destFile.writeText("PHI_ORIGINAL")

        val result = repo.importModelFromStream(
            displayName = modelFileName,
            totalBytes = 0L,
            openStream = { ByteArrayInputStream(ByteArray(0)) }
        )

        assertTrue(result.isFailure)
        assertEquals("PHI_ORIGINAL", destFile.readText())

        val remainingTmpFiles = modelsDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertEquals(0, remainingTmpFiles.size)
    }

    @Test
    fun successfulSameNameReimport_replacesAtomicallyAndPreservesCustomSettings() = runBlocking {
        val modelFileName = "gemma4_pixel.litertlm"
        val destFile = File(modelsDir, modelFileName)
        destFile.writeText("OLD_VERSION_1")

        val existingId = db.modelProfileDao().upsertModel(
            ModelProfileEntity(
                displayName = "gemma4 pixel",
                filePath = destFile.absolutePath,
                fileSizeBytes = "OLD_VERSION_1".length.toLong(),
                supportsVision = true,
                preferredBackend = "GPU",
                maxNumTokens = 4096,
                temperature = 0.4f,
                topK = 40,
                topP = 0.88f,
                thinkingEnabled = false
            )
        )

        val newBytes = "NEW_UPGRADED_MODEL_DATA_VERSION_2".toByteArray(Charsets.UTF_8)
        val result = repo.importModelFromStream(
            displayName = modelFileName,
            totalBytes = newBytes.size.toLong(),
            openStream = { ByteArrayInputStream(newBytes) }
        )

        assertTrue(result.isSuccess)
        assertEquals("NEW_UPGRADED_MODEL_DATA_VERSION_2", destFile.readText(Charsets.UTF_8))
        assertEquals(newBytes.size.toLong(), destFile.length())

        // Check temp files cleaned up
        val remainingTmpFiles = modelsDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertEquals(0, remainingTmpFiles.size)

        // Check DB entry was updated with new size while preserving user custom settings
        val updatedProfile = db.modelProfileDao().getModelById(existingId)
        assertEquals(newBytes.size.toLong(), updatedProfile?.fileSizeBytes)
        assertEquals(true, updatedProfile?.supportsVision)
        assertEquals(0.4f, updatedProfile?.temperature)
        assertEquals(false, updatedProfile?.thinkingEnabled)
    }
}
