package com.example.data.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.automation.AutomationScheduler
import com.example.agent.sandbox.CodeSandboxRunner
import com.example.agent.tools.AgentApprovalCoordinator
import com.example.agent.tools.AndroidSystemActions
import com.example.agent.tools.MobHarnToolkitRegistry
import com.example.agent.tools.ToolExecutionMonitor
import com.example.data.MobHarnRepository
import com.example.data.db.MobHarnDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReversibleWorkspaceWritesTest {

    private lateinit var context: Context
    private lateinit var db: MobHarnDatabase
    private lateinit var workspace: WorkspaceManager
    private lateinit var repo: MobHarnRepository
    private lateinit var registry: MobHarnToolkitRegistry
    private val project = "rev_test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        File(context.filesDir, "workspace/$project").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, MobHarnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workspace = WorkspaceManager(context)
        repo = MobHarnRepository(
            context = context,
            db = db,
            vault = MarkdownVaultManager(context),
            workspace = workspace,
            prefs = PreferencesRepository(context)
        )
        val monitor = ToolExecutionMonitor(repo, AgentApprovalCoordinator()).apply {
            activeProjectName = project
            requireApprovalForExternal = false
        }
        registry = MobHarnToolkitRegistry(
            repository = repo,
            system = AndroidSystemActions(context),
            sandbox = CodeSandboxRunner(context),
            scheduler = AutomationScheduler(context),
            monitor = monitor
        )
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "workspace/$project").deleteRecursively()
    }

    private fun projectFile(relativePath: String): File =
        File(context.filesDir, "workspace/$project/$relativePath")

    @Test
    fun undoOfNewlyCreatedFileDeletesAbsentFileInsteadOfLeavingEmptyFile() = runBlocking {
        registry.codingTools.write_workspace_file("created.txt", "ciao\n", "crea file")
        val target = projectFile("created.txt")
        assertTrue(target.exists())

        val undone = repo.rollbackLatestSnapshot(project, "created.txt")
        assertTrue("Rollback must succeed for a newly created file", undone)
        assertFalse("Undoing creation of an absent file must remove it, not leave a 0-byte file", target.exists())
    }

    @Test
    fun writePatchAndUndoExposeBoundedRealDiffAndUndoTool() = runBlocking {
        val createOut = registry.codingTools.write_workspace_file("app.js", "const a = 1;\nconst b = 2;\n", "init")
        assertTrue("Write output must contain real + diff lines: $createOut", createOut.contains("+const a = 1;"))

        val patchOut = registry.codingTools.patch_workspace_file("app.js", "const b = 2;", "const b = 42;")
        assertTrue("Patch output must contain removed line: $patchOut", patchOut.contains("-const b = 2;"))
        assertTrue("Patch output must contain added line: $patchOut", patchOut.contains("+const b = 42;"))

        val undoOut = registry.invokeToolByName(
            "undo_workspace_file",
            mapOf("relativePath" to "app.js"),
            setOf("coding")
        ).orEmpty()
        assertFalse("Undo tool must be exposed in recovered dispatch: $undoOut", undoOut.contains("non disponibile"))
        assertEquals("const a = 1;\nconst b = 2;\n", projectFile("app.js").readText(Charsets.UTF_8))
    }

    @Test
    fun partialReadBlocksDestructiveFullOverwrite() = runBlocking {
        val original = (1..240).joinToString("\n") { "line-$it" } + "\n"
        projectFile("big.txt").apply {
            parentFile?.mkdirs()
            writeText(original, Charsets.UTF_8)
        }

        val excerpt = registry.codingTools.read_file_lines("big.txt", 1, 10)
        assertTrue(excerpt.contains("parziale", ignoreCase = true))

        val writeOut = registry.codingTools.write_workspace_file("big.txt", "truncated\n", "bad overwrite")
        assertTrue("Overwrite after partial read must fail: $writeOut", writeOut.contains("Errore") || writeOut.contains("parziale", ignoreCase = true))
        assertEquals("Original file must remain intact after rejected overwrite", original, projectFile("big.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun concurrentExternalEditIsNotClobberedByUndo() = runBlocking {
        registry.codingTools.write_workspace_file("notes.txt", "v1\n", "v1")
        registry.codingTools.patch_workspace_file("notes.txt", "v1", "v2")
        projectFile("notes.txt").writeText("external_edit\n", Charsets.UTF_8)

        val undone = repo.rollbackLatestSnapshot(project, "notes.txt")
        assertFalse("Undo must refuse to clobber a concurrent external edit", undone)
        assertEquals("external_edit\n", projectFile("notes.txt").readText(Charsets.UTF_8))
        assertEquals(2, repo.observeSnapshots(project).first().size)
    }

    @Test
    fun invalidUtf8FileIsRejectedAndNotCorrupted() = runBlocking {
        val rawBinary = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x41)
        val file = projectFile("bin.dat").apply {
            parentFile?.mkdirs()
            writeBytes(rawBinary)
        }

        val out = registry.codingTools.write_workspace_file("bin.dat", "text", "overwrite binary")
        assertTrue("Overwriting invalid UTF-8 file must be rejected: $out", out.contains("Errore") || out.contains("UTF-8", ignoreCase = true))
        assertArrayEquals("Raw bytes must remain untouched", rawBinary, file.readBytes())
    }

    @Test
    fun oversizedWriteIsRejectedWithoutWritingFile() = runBlocking {
        val huge = "a".repeat(300_000)
        val out = registry.codingTools.write_workspace_file("huge.txt", huge, "too big")
        assertTrue("Oversized write must fail: $out", out.contains("Errore") || out.contains("grande", ignoreCase = true))
        assertFalse("Oversized write must not create the file", projectFile("huge.txt").exists())
    }

    @Test
    fun checkpointFailureBeforeWriteLeavesExistingFileUntouched() = runBlocking {
        val file = projectFile("keep.txt").apply {
            parentFile?.mkdirs()
            writeText("keep-me\n", Charsets.UTF_8)
        }
        registry.codingTools.read_file_lines("keep.txt", 1, 50)
        db.close()

        val out = registry.codingTools.write_workspace_file("keep.txt", "overwritten\n", "should fail")
        assertTrue("Write must fail when checkpoint persistence fails: $out", out.contains("Errore"))
        assertEquals("Existing file must remain untouched if checkpoint cannot be persisted", "keep-me\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun restoreFailureOnUndoPreservesCheckpointAndReturnsFalse() = runBlocking {
        registry.codingTools.write_workspace_file("dirfail.txt", "v1\n", "v1")
        registry.codingTools.patch_workspace_file("dirfail.txt", "v1", "v2")
        val target = projectFile("dirfail.txt")
        assertTrue(target.delete())
        assertTrue(target.mkdirs())
        File(target, "child.txt").writeText("block", Charsets.UTF_8)

        val snapshotsBefore = repo.observeSnapshots(project).first().size
        val undone = repo.rollbackLatestSnapshot(project, "dirfail.txt")
        assertFalse("Rollback must return false when restore fails", undone)
        assertEquals(
            "Checkpoint must be preserved in Room when restore fails",
            snapshotsBefore,
            repo.observeSnapshots(project).first().size
        )
    }
}
