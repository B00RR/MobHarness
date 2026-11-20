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
import com.example.data.db.WorkspaceSnapshotEntity
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import com.example.di.MobHarnAppContainer
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

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
        repo.beforeFileWriteSwapForTest = null
        repo.beforeUndoRestoreForTest = null
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
    fun undoOfExistingEmptyFileRestoresZeroByteFileWithoutDeleting() = runBlocking {
        val target = projectFile("empty.txt").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(0))
        }
        assertTrue(target.exists() && target.length() == 0L)

        registry.codingTools.read_file_lines("empty.txt", 1, 50)
        val writeOut = registry.codingTools.write_workspace_file("empty.txt", "filled\n", "fill empty")
        assertTrue(writeOut.contains("+filled"))
        assertEquals("filled\n", target.readText(Charsets.UTF_8))

        val undoOut = registry.codingTools.undo_workspace_file("empty.txt")
        assertTrue(undoOut.contains("-filled"))
        assertTrue("Existing 0-byte file must remain present after undo", target.exists())
        assertEquals(0L, target.length())
    }

    @Test
    fun legacySnapshotRestoresContentWithoutDeletingAndChecksExternalEdits() = runBlocking {
        val legacyText = projectFile("legacy_text.txt").apply {
            parentFile?.mkdirs()
            writeText("testo nuovo", Charsets.UTF_8)
        }
        db.workspaceSnapshotDao().insertSnapshot(
            WorkspaceSnapshotEntity(
                projectName = project,
                relativePath = "legacy_text.txt",
                previousContent = "testo vecchio",
                newContent = "testo nuovo",
                summary = "v1.2.0 snapshot"
            )
        )

        // External edit must block legacy undo and preserve checkpoint.
        legacyText.writeText("esterno", Charsets.UTF_8)
        assertTrue(repo.undoLatestWorkspaceChange(project, "legacy_text.txt").isFailure)
        assertEquals("esterno", legacyText.readText(Charsets.UTF_8))
        assertNotNull(db.workspaceSnapshotDao().getLatestSnapshot(project, "legacy_text.txt"))

        // When disk matches legacy newContent, undo restores exact legacy previousContent.
        legacyText.writeText("testo nuovo", Charsets.UTF_8)
        val restored = repo.undoLatestWorkspaceChange(project, "legacy_text.txt").getOrThrow()
        assertTrue(restored.existsAfter)
        assertEquals("testo vecchio", legacyText.readText(Charsets.UTF_8))

        // Legacy empty previousContent must NOT guess deletion.
        val legacyEmpty = projectFile("legacy_empty.txt").apply {
            writeText("after", Charsets.UTF_8)
        }
        db.workspaceSnapshotDao().insertSnapshot(
            WorkspaceSnapshotEntity(
                projectName = project,
                relativePath = "legacy_empty.txt",
                previousContent = "",
                newContent = "after",
                summary = "v1.2.0 ambiguous empty"
            )
        )
        val restoredEmpty = repo.undoLatestWorkspaceChange(project, "legacy_empty.txt").getOrThrow()
        assertTrue(restoredEmpty.existsAfter)
        assertTrue("Legacy empty snapshot must keep file present as 0 bytes", legacyEmpty.exists())
        assertEquals(0L, legacyEmpty.length())
    }

    @Test
    fun restoreIsByteExactForCrlfBomUtf8AndNoTrailingNewline() = runBlocking {
        val exactBytes = ("\uFEFFprima riga caffè 🚀\r\nseconda riga senza newline").toByteArray(Charsets.UTF_8)
        val file = projectFile("exact.txt").apply {
            parentFile?.mkdirs()
            writeBytes(exactBytes)
        }

        registry.codingTools.read_file_lines("exact.txt", 1, 50)
        registry.codingTools.patch_workspace_file("exact.txt", "seconda riga", "riga modificata")
        assertFalse(file.readBytes().contentEquals(exactBytes))

        val undoRes = repo.undoLatestWorkspaceChange(project, "exact.txt").getOrThrow()
        assertTrue(undoRes.existsAfter)
        assertArrayEquals("Restored file must match original bytes exactly", exactBytes, file.readBytes())
    }

    @Test
    fun writePatchAndUndoExposeBoundedRealDiffAndUndoTool() = runBlocking {
        val createOut = registry.codingTools.write_workspace_file("app.js", "const a = 1;\nconst b = 2;\n", "init")
        assertTrue("Write output must contain real + diff lines: $createOut", createOut.contains("+const a = 1;"))

        val patchOut = registry.codingTools.patch_workspace_file("app.js", "const b = 2;", "const b = 42;")
        assertTrue("Patch output must contain removed line: $patchOut", patchOut.contains("-const b = 2;"))
        assertTrue("Patch output must contain added line: $patchOut", patchOut.contains("+const b = 42;"))

        // Recovered dispatch preserves whitespace and empty replacement strings, and gates on coding group.
        val gatedOut = registry.invokeToolByName(
            "undo_workspace_file",
            mapOf("relativePath" to "app.js"),
            emptySet()
        ).orEmpty()
        assertTrue(gatedOut.contains("non attivo", ignoreCase = true))

        val undoOut = registry.invokeToolByName(
            "undo_workspace_file",
            mapOf("relativePath" to "app.js"),
            setOf("coding")
        ).orEmpty()
        assertFalse("Undo tool must be exposed in recovered dispatch: $undoOut", undoOut.contains("non disponibile"))
        assertTrue("Undo output must include real diff: $undoOut", undoOut.contains("-const b = 42;") && undoOut.contains("+const b = 2;"))
        assertEquals("const a = 1;\nconst b = 2;\n", projectFile("app.js").readText(Charsets.UTF_8))

        // Patch via recovered dispatch preserving leading indentation and allowing empty newText.
        registry.codingTools.write_workspace_file("indent.js", "function f() {\n  const x = 1;\n}\n", "indent")
        val recPatch = registry.invokeToolByName(
            "patch_workspace_file",
            mapOf("relativePath" to "indent.js", "oldText" to "  const x = 1;\n", "newText" to ""),
            setOf("coding")
        ).orEmpty()
        assertTrue(recPatch.contains("-  const x = 1;"))
        assertEquals("function f() {\n}\n", projectFile("indent.js").readText(Charsets.UTF_8))
    }

    @Test
    fun diffOutputIsBoundedForLargeRewrites() {
        val oldBytes = (1..300).joinToString("\n") { "old_line_$it " + "a".repeat(200) }.toByteArray(Charsets.UTF_8)
        val newBytes = (1..300).joinToString("\n") { "new_line_$it " + "b".repeat(200) }.toByteArray(Charsets.UTF_8)
        val diff = buildBoundedUnifiedDiff("large.txt", true, oldBytes, true, newBytes)
        assertTrue("Diff must stay bounded in characters: ${diff.length}", diff.length <= MAX_DIFF_OUTPUT_CHARS + 120)
        assertTrue("Diff must show real removals", diff.contains("-old_line_1"))
        assertTrue("Diff must note truncation", diff.contains("troncato", ignoreCase = true))
    }

    @Test
    fun partialReadBlocksDestructiveFullOverwriteEvenAfterPatch() = runBlocking {
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

        // Surgical patch after partial read IS allowed.
        val patchOut = registry.codingTools.patch_workspace_file("big.txt", "line-2\n", "line-2-edited\n")
        assertTrue("Surgical patch after partial read must succeed: $patchOut", patchOut.contains("+line-2-edited"))
        assertTrue(projectFile("big.txt").readText(Charsets.UTF_8).contains("line-240"))

        // Patch must NOT promote a partial read into permission for a full overwrite.
        val writeAfterPatch = registry.codingTools.write_workspace_file("big.txt", "still-truncated\n", "bad overwrite 2")
        assertTrue("Full overwrite after partial read + patch must still fail: $writeAfterPatch", writeAfterPatch.contains("Errore"))
        assertTrue(projectFile("big.txt").readText(Charsets.UTF_8).contains("line-240"))
    }

    @Test
    fun unreadExistingFileRejectsBlindAgentOverwriteButAllowsFullReadOverwrite() = runBlocking {
        val file = projectFile("existing.js").apply {
            parentFile?.mkdirs()
            writeText("const keep = 1;\n", Charsets.UTF_8)
        }
        val blindOut = registry.codingTools.write_workspace_file("existing.js", "const clobber = 2;\n", "blind")
        assertTrue("Blind overwrite of unread existing file must fail: $blindOut", blindOut.contains("Errore"))
        assertEquals("const keep = 1;\n", file.readText(Charsets.UTF_8))

        val fullRead = registry.codingTools.read_file_lines("existing.js", 1, 50)
        assertFalse(fullRead.contains("parziale", ignoreCase = true))

        val okOut = registry.codingTools.write_workspace_file("existing.js", "const updated = 2;\n", "after full read")
        assertTrue(okOut.contains("+const updated = 2;"))
        assertEquals("const updated = 2;\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun concurrentExternalEditBlocksWritePatchSwapAndUndo() = runBlocking {
        // 1. External edit between read and write/patch
        val file = projectFile("notes.txt").apply {
            parentFile?.mkdirs()
            writeText("v1\n", Charsets.UTF_8)
        }
        registry.codingTools.read_file_lines("notes.txt", 1, 50)
        file.writeText("external_after_read\n", Charsets.UTF_8)

        val staleWrite = registry.codingTools.write_workspace_file("notes.txt", "agent_v2\n", "stale write")
        assertTrue("Stale write must fail on concurrent external edit: $staleWrite", staleWrite.contains("Conflitto"))
        assertEquals("external_after_read\n", file.readText(Charsets.UTF_8))

        val stalePatch = registry.codingTools.patch_workspace_file("notes.txt", "external_after_read", "agent_patch")
        assertTrue("Stale patch must fail on concurrent external edit: $stalePatch", stalePatch.contains("Conflitto"))
        assertEquals("external_after_read\n", file.readText(Charsets.UTF_8))

        // 2. External edit occurring right between checkpoint insert and atomic file swap
        registry.codingTools.read_file_lines("notes.txt", 1, 50)
        var checkpointObservedBeforeSwap = false
        repo.beforeFileWriteSwapForTest = { p, rel, snapId ->
            val persisted = db.workspaceSnapshotDao().getLatestSnapshot(p, rel)
            checkpointObservedBeforeSwap = persisted != null &&
                persisted.id == snapId &&
                file.readText(Charsets.UTF_8) == "external_after_read\n"
            file.writeText("raced_external\n", Charsets.UTF_8)
        }
        val raceRes = repo.writeWorkspaceFile(project, "notes.txt", "agent_raced\n", "race", WorkspaceWritePolicy.AgentGuarded)
        repo.beforeFileWriteSwapForTest = null
        assertTrue("Checkpoint must already be persisted before file swap", checkpointObservedBeforeSwap)
        assertTrue("Race during swap must fail instead of clobbering", raceRes.isFailure)
        assertEquals("raced_external\n", file.readText(Charsets.UTF_8))

        // 3. External edit before undo
        registry.codingTools.write_workspace_file("undo_race.txt", "v1\n", "v1")
        registry.codingTools.patch_workspace_file("undo_race.txt", "v1", "v2")
        projectFile("undo_race.txt").writeText("external_edit\n", Charsets.UTF_8)

        val undone = repo.rollbackLatestSnapshot(project, "undo_race.txt")
        assertFalse("Undo must refuse to clobber a concurrent external edit", undone)
        assertEquals("external_edit\n", projectFile("undo_race.txt").readText(Charsets.UTF_8))
        assertEquals(2, repo.observeSnapshots(project).first().count { it.relativePath == "undo_race.txt" })
    }

    @Test
    fun invalidUtf8AndOversizedFilesAreRejectedWithoutCorruption() = runBlocking {
        val rawBinary = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x41)
        val binFile = projectFile("bin.dat").apply {
            parentFile?.mkdirs()
            writeBytes(rawBinary)
        }

        val writeBin = registry.codingTools.write_workspace_file("bin.dat", "text", "overwrite binary")
        assertTrue("Overwriting invalid UTF-8 file must be rejected: $writeBin", writeBin.contains("UTF-8", ignoreCase = true))
        assertArrayEquals("Raw bytes must remain untouched", rawBinary, binFile.readBytes())

        val patchBin = registry.codingTools.patch_workspace_file("bin.dat", "A", "B")
        assertTrue("Patching invalid UTF-8 file must be rejected: $patchBin", patchBin.contains("UTF-8", ignoreCase = true))
        assertArrayEquals(rawBinary, binFile.readBytes())

        // Lone UTF-16 surrogate in outgoing content
        val badSurrogate = "bad\uD800text"
        val badOut = registry.codingTools.write_workspace_file("surrogate.txt", badSurrogate, "bad utf8")
        assertTrue("Unpaired surrogate must be rejected: $badOut", badOut.contains("UTF-8", ignoreCase = true))
        assertFalse(projectFile("surrogate.txt").exists())

        // Oversized new file
        val huge = "a".repeat(300_000)
        val hugeOut = registry.codingTools.write_workspace_file("huge.txt", huge, "too big")
        assertTrue("Oversized write must fail: $hugeOut", hugeOut.contains("grande", ignoreCase = true))
        assertFalse("Oversized write must not create the file", projectFile("huge.txt").exists())

        // Oversized existing file cannot be patched or overwritten
        val existingBig = projectFile("existing_big.txt").apply {
            writeBytes(ByteArray(MAX_EDITABLE_FILE_BYTES + 128) { 'x'.code.toByte() })
        }
        val patchBig = registry.codingTools.patch_workspace_file("existing_big.txt", "xxx", "yyy")
        assertTrue("Oversized existing file patch must fail: $patchBig", patchBig.contains("grande", ignoreCase = true))
        assertEquals((MAX_EDITABLE_FILE_BYTES + 128).toLong(), existingBig.length())
    }

    @Test
    fun checkpointPersistedBeforeWriteAndDiskFaultCleansUpSafely() = runBlocking {
        val file = projectFile("keep.txt").apply {
            parentFile?.mkdirs()
            writeText("keep-me\n", Charsets.UTF_8)
        }
        registry.codingTools.read_file_lines("keep.txt", 1, 50)

        // Fault 1: disk write throws after checkpoint insert -> file untouched, temp files cleaned up, no dangling checkpoint.
        var sawCheckpointBeforeDiskWrite = false
        repo.beforeFileWriteSwapForTest = { p, rel, snapId ->
            sawCheckpointBeforeDiskWrite = db.workspaceSnapshotDao().getLatestSnapshot(p, rel)?.id == snapId
            throw IOException("Simulated disk full")
        }
        val diskFail = repo.writeWorkspaceFile(project, "keep.txt", "should-not-land\n", "fail", WorkspaceWritePolicy.AgentGuarded)
        repo.beforeFileWriteSwapForTest = null
        assertTrue("Checkpoint must be persisted before disk write attempt", sawCheckpointBeforeDiskWrite)
        assertTrue(diskFail.isFailure)
        assertEquals("keep-me\n", file.readText(Charsets.UTF_8))
        val strayTemps = file.parentFile?.listFiles { _, name -> name.startsWith(TEMP_FILE_PREFIX) }.orEmpty()
        assertEquals("Temp files must be cleaned up on write failure", 0, strayTemps.size)
        assertEquals("Uncommitted checkpoint should be removed when target file is untouched", 0, repo.observeSnapshots(project).first().size)

        // Fault 2: DB checkpoint persistence fails (DB closed) -> disk file never touched.
        db.close()
        val out = registry.codingTools.write_workspace_file("keep.txt", "overwritten\n", "should fail")
        assertTrue("Write must fail when checkpoint persistence fails: $out", out.contains("Errore"))
        assertEquals("Existing file must remain untouched if checkpoint cannot be persisted", "keep-me\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun restoreFailureOnUndoPreservesCheckpointAndAllowsRetry() = runBlocking {
        registry.codingTools.write_workspace_file("retry.txt", "v1\n", "v1")
        registry.codingTools.patch_workspace_file("retry.txt", "v1", "v2")
        val target = projectFile("retry.txt")

        // Fault 1: transient I/O error during undo -> checkpoint preserved, file still v2, subsequent retry succeeds!
        repo.beforeUndoRestoreForTest = { _, _, _ -> throw IOException("Simulated transient I/O error") }
        val failedUndo = repo.undoLatestWorkspaceChange(project, "retry.txt")
        repo.beforeUndoRestoreForTest = null
        assertTrue(failedUndo.isFailure)
        assertEquals("v2\n", target.readText(Charsets.UTF_8))
        assertEquals("Checkpoint must be preserved after failed undo", 2, repo.observeSnapshots(project).first().size)

        val retriedUndo = repo.undoLatestWorkspaceChange(project, "retry.txt")
        assertTrue("Retry after transient fault must succeed", retriedUndo.isSuccess)
        assertEquals("v1\n", target.readText(Charsets.UTF_8))
        assertEquals(1, repo.observeSnapshots(project).first().size)

        // Fault 2: target replaced by non-empty directory -> undo fails and preserves remaining checkpoint.
        assertTrue(target.delete())
        assertTrue(target.mkdirs())
        File(target, "child.txt").writeText("block", Charsets.UTF_8)

        val undone = repo.rollbackLatestSnapshot(project, "retry.txt")
        assertFalse("Rollback must return false when restore fails", undone)
        assertEquals("Checkpoint must be preserved in Room when restore fails", 1, repo.observeSnapshots(project).first().size)
    }

    @Test
    fun multiStepLifoUndoRestoresChainAndStudioAndChatSaveUseSharedRepository() = runBlocking {
        // Multi-step LIFO chain in fast succession (same millisecond safe)
        registry.codingTools.write_workspace_file("chain.txt", "step1\n", "s1")
        registry.codingTools.patch_workspace_file("chain.txt", "step1", "step2")
        registry.codingTools.patch_workspace_file("chain.txt", "step2", "step3")
        assertEquals("step3\n", projectFile("chain.txt").readText(Charsets.UTF_8))

        registry.monitor.beginTurn()
        registry.codingTools.undo_workspace_file("chain.txt")
        assertEquals("step2\n", projectFile("chain.txt").readText(Charsets.UTF_8))
        registry.codingTools.undo_workspace_file("chain.txt")
        assertEquals("step1\n", projectFile("chain.txt").readText(Charsets.UTF_8))
        registry.codingTools.undo_workspace_file("chain.txt")
        assertFalse(projectFile("chain.txt").exists())

        // Studio & Chat ViewModels share the app container's repository with reversible checkpoints & conflict protection
        val container = MobHarnAppContainer.getInstance(context)
        val appProject = "shared_ui_test"
        File(context.filesDir, "workspace/$appProject").deleteRecursively()
        try {
            container.repository.workspace.createProject(appProject)
            container.repository.prefs.setActiveProject(appProject)

            val studioVm = StudioViewModel(context, container)
            studioVm.selectProject(appProject)
            waitUntil { studioVm.uiState.value.activeProject == appProject }

            studioVm.createNewFile("studio_file.js")
            waitUntil { studioVm.uiState.value.selectedFile == "studio_file.js" && studioVm.uiState.value.loadedFileSha256 != null }
            val studioFile = container.workspaceManager.inspectFileState(appProject, "studio_file.js").file
            assertTrue("Missing $studioFile, project=${studioVm.uiState.value.activeProject}, files=${container.workspaceManager.listFiles(appProject)}", studioFile.isFile)
            assertTrue(studioVm.uiState.value.lastDiffPreview.orEmpty().contains("+++ b/studio_file.js"))

            // Concurrent external edit while open in Studio must block Studio save from clobbering
            studioVm.updateEditorContent("console.log('from studio');\n")
            studioFile.writeText("console.log('external');\n", Charsets.UTF_8)
            studioVm.saveCurrentFile()
            delay(150)
            assertEquals("Studio save must not clobber concurrent external edit", "console.log('external');\n", studioFile.readText(Charsets.UTF_8))

            // Reloading and saving succeeds and updates diff preview; rollback restores previous content
            studioVm.selectFile("studio_file.js")
            waitUntil { studioVm.uiState.value.editorContent == "console.log('external');\n" }
            studioVm.updateEditorContent("console.log('from studio v2');\n")
            studioVm.saveCurrentFile()
            waitUntil { !studioVm.uiState.value.isDirty }
            assertEquals("console.log('from studio v2');\n", studioFile.readText(Charsets.UTF_8))
            assertTrue(studioVm.uiState.value.lastDiffPreview.orEmpty().contains("+console.log('from studio v2');"))

            studioVm.rollbackSelectedFile()
            waitUntil { studioFile.readText(Charsets.UTF_8) == "console.log('external');\n" }
            assertEquals("console.log('external');\n", studioFile.readText(Charsets.UTF_8))

            // Chat code-block save uses shared repository and is reversible via undo
            val chatVm = ChatViewModel(context, container)
            val prefsReady = async { chatVm.preferences.first { it.activeProjectName == appProject } }
            try { waitUntil { prefsReady.isCompleted } } finally { prefsReady.cancel() }
            val beforeFiles = container.repository.workspace.listFiles(appProject).map { it.relativePath }.toSet()
            chatVm.saveCodeBlockToWorkspace("js", "console.log('from chat');\n")
            waitUntil {
                container.repository.workspace.listFiles(appProject).any { it.relativePath !in beforeFiles }
            }
            val savedSnippet = container.repository.workspace.listFiles(appProject)
                .first { it.relativePath !in beforeFiles }
                .relativePath
            val chatUndo = container.repository.undoLatestWorkspaceChange(appProject, savedSnippet).getOrThrow()
            assertFalse("Undoing chat snippet creation must delete the new snippet file", chatUndo.existsAfter)
            assertFalse(File(context.filesDir, "workspace/$appProject/$savedSnippet").exists())
        } finally {
            File(context.filesDir, "workspace/$appProject").deleteRecursively()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            delay(25)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }
}
