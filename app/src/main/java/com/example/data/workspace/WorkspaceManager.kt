package com.example.data.workspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WorkspaceFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String
)

data class CodeSearchMatch(
    val relativePath: String,
    val lineNumber: Int,
    val lineContent: String
)

internal data class RawExcerptResult(
    val formattedText: String,
    val isPartial: Boolean,
    val fullFileBytes: ByteArray?
)

internal fun readExcerptDetailed(input: java.io.InputStream, size: Long, start: Int, end: Int, path: String): RawExcerptResult {
    require(start > 0 && end >= start) { "Intervallo di righe non valido" }
    val last = minOf(end.toLong(), start.toLong() + 199).toInt()
    val bytes = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(4096)
    var newlines = 0
    while (newlines < last && bytes.size() < 65_536) {
        val n = input.read(chunk, 0, minOf(chunk.size, 65_536 - bytes.size()))
        if (n <= 0) break
        bytes.write(chunk, 0, n)
        for (i in 0 until n) if (chunk[i] == 10.toByte()) newlines++
    }
    val more = bytes.size().toLong() < size
    val rawBytes = bytes.toByteArray()
    val prefix = String(rawBytes, Charsets.UTF_8)
    // Don't present a split UTF-8 character or incomplete final line as file content.
    val complete = if (more) prefix.substring(0, prefix.lastIndexOfAny(charArrayOf('\n', '\r')) + 1) else prefix
    return complete.reader().buffered().useLines { lines ->
        val rows = lines.drop(start - 1).iterator()
        val content = buildString {
            for (i in start..last) {
                if (!rows.hasNext()) break
                append("\n${i.toString().padStart(4)} | ${rows.next()}")
            }
        }
        val partial = more || start > 1 || rows.hasNext()
        val note = if (partial) " (lettura parziale)" else ""
        val formatted = "File: $path$note" + content.ifEmpty { "\nNessuna riga completa nell'intervallo letto." }
        RawExcerptResult(
            formattedText = formatted,
            isPartial = partial,
            fullFileBytes = if (!more) rawBytes else null
        )
    }
}

internal fun readExcerpt(input: java.io.InputStream, size: Long, start: Int, end: Int, path: String): String =
    readExcerptDetailed(input, size, start, end, path).formattedText

internal data class WorkspaceFileState(
    val normalizedPath: String,
    val file: File,
    val exists: Boolean,
    val bytes: ByteArray?,
    val sha256: String?,
    val sizeBytes: Long,
    val lastModified: Long
)

class WorkspaceManager(context: Context) {

    private val workspaceRoot: File = File(context.filesDir, "workspace").apply { mkdirs() }

    suspend fun ensureStarterProject() = withContext(Dispatchers.IO) {
        val starterDir = File(workspaceRoot, "starter_web")
        if (!starterDir.exists() || starterDir.listFiles().isNullOrEmpty()) {
            starterDir.mkdirs()
            File(starterDir, "index.html").writeText(STARTER_HTML)
            File(starterDir, "app.js").writeText(STARTER_JS)
            File(starterDir, "notes.sql").writeText(STARTER_SQL)
        }
    }

    suspend fun listProjects(): List<String> = withContext(Dispatchers.IO) {
        ensureStarterProject()
        workspaceRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: listOf("starter_web")
    }

    suspend fun createProject(rawName: String): String = withContext(Dispatchers.IO) {
        val safe = sanitizeSegment(rawName).ifBlank { "project_${System.currentTimeMillis() % 10000}" }
        val dir = File(workspaceRoot, safe)
        dir.mkdirs()
        val readme = File(dir, "README.md")
        if (!readme.exists()) {
            readme.writeText("# $safe\n\nProgetto creato nel Coding Workspace di MobHarn.\n")
        }
        safe
    }

    suspend fun listFiles(projectName: String): List<WorkspaceFileInfo> = withContext(Dispatchers.IO) {
        val root = projectDir(projectName)
        if (!root.exists()) return@withContext emptyList()
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(TEMP_FILE_PREFIX) }
            .map { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                WorkspaceFileInfo(
                    relativePath = rel,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase()
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    suspend fun readFile(projectName: String, relativePath: String): String? = withContext(Dispatchers.IO) {
        val file = resolveSafeFile(projectName, relativePath) ?: return@withContext null
        if (!file.exists() || !file.isFile) return@withContext null
        file.readText(Charsets.UTF_8)
    }

    suspend fun readFileLines(
        projectName: String,
        relativePath: String,
        startLine: Int = 1,
        endLine: Int = 200
    ): Result<String> = readFileLinesWithMeta(projectName, relativePath, startLine, endLine)
        .map { it.formattedText }

    internal suspend fun readFileLinesWithMeta(
        projectName: String,
        relativePath: String,
        startLine: Int = 1,
        endLine: Int = 200
    ): Result<WorkspaceReadExcerpt> = withContext(Dispatchers.IO) {
        runCatching {
            val (normPath, file) = resolveSafeFileInternal(projectName, relativePath)
                ?: throw SecurityException("Percorso non valido: $relativePath")
            require(file.isFile) { "File non trovato: $relativePath" }
            val (raw, size) = file.inputStream().use { input ->
                val fileSize = input.channel.size()
                readExcerptDetailed(input, fileSize, startLine, endLine, normPath) to fileSize
            }
            val sha = when {
                raw.fullFileBytes != null -> sha256Hex(raw.fullFileBytes)
                size <= MAX_EDITABLE_FILE_BYTES.toLong() ->
                    runCatching { sha256Hex(readBoundedBytes(file, MAX_EDITABLE_FILE_BYTES)) }.getOrNull()
                else -> null
            }
            WorkspaceReadExcerpt(
                normalizedPath = normPath,
                formattedText = raw.formattedText,
                isPartial = raw.isPartial,
                sha256 = sha,
                sizeBytes = size
            )
        }
    }

    internal fun normalizeRelativePath(projectName: String, relativePath: String): String? =
        resolveSafeFileInternal(projectName, relativePath)?.first

    internal fun inspectFileState(
        projectName: String,
        relativePath: String,
        maxBytes: Int = MAX_EDITABLE_FILE_BYTES
    ): WorkspaceFileState {
        val (normPath, file) = resolveSafeFileInternal(projectName, relativePath)
            ?: throw SecurityException("Percorso non valido: $relativePath")
        if (!file.exists()) {
            return WorkspaceFileState(
                normalizedPath = normPath,
                file = file,
                exists = false,
                bytes = null,
                sha256 = null,
                sizeBytes = 0L,
                lastModified = 0L
            )
        }
        if (!file.isFile) {
            throw IllegalArgumentException("Il percorso non è un file regolare: $normPath")
        }
        val bytes = readBoundedBytes(file, maxBytes)
        return WorkspaceFileState(
            normalizedPath = normPath,
            file = file,
            exists = true,
            bytes = bytes,
            sha256 = sha256Hex(bytes),
            sizeBytes = bytes.size.toLong(),
            lastModified = file.lastModified()
        )
    }

    internal suspend fun writeBytesChecked(
        targetFile: File,
        newBytes: ByteArray,
        expectedBeforeExists: Boolean,
        expectedBeforeSha256: String?,
        beforeSwap: (suspend () -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val parent = targetFile.parentFile
            ?: throw java.io.IOException("Directory padre mancante per '${targetFile.name}'.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw java.io.IOException("Impossibile creare la directory per '${targetFile.name}'.")
        }
        val tempFile = File(
            parent,
            "$TEMP_FILE_PREFIX${targetFile.name}.${java.util.UUID.randomUUID().toString().take(8)}.tmp"
        )
        var swapped = false
        try {
            java.io.FileOutputStream(tempFile).use { fos ->
                fos.write(newBytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            beforeSwap?.invoke()
            verifyDiskMatchesExpected(targetFile, expectedBeforeExists, expectedBeforeSha256)
            replaceFileAtomically(tempFile, targetFile)
            swapped = true
        } finally {
            if (!swapped && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    internal suspend fun deleteFileChecked(
        targetFile: File,
        expectedSha256: String,
        beforeDelete: (suspend () -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        beforeDelete?.invoke()
        verifyDiskMatchesExpected(targetFile, expectedExists = true, expectedSha256 = expectedSha256)
        if (!targetFile.delete() || targetFile.exists()) {
            throw java.io.IOException("Impossibile eliminare '${targetFile.name}' durante il ripristino.")
        }
    }

    internal fun diskMatchesState(targetFile: File, expectedExists: Boolean, expectedSha256: String?): Boolean {
        return runCatching {
            if (!expectedExists) {
                !targetFile.exists()
            } else {
                targetFile.exists() &&
                    targetFile.isFile &&
                    sha256Hex(readBoundedBytes(targetFile, MAX_EDITABLE_FILE_BYTES)) == expectedSha256
            }
        }.getOrDefault(false)
    }

    private fun verifyDiskMatchesExpected(targetFile: File, expectedExists: Boolean, expectedSha256: String?) {
        if (!expectedExists) {
            if (targetFile.exists()) {
                throw ConcurrentWorkspaceEditException(
                    "Conflitto: '${targetFile.name}' è stato creato esternamente durante l'operazione."
                )
            }
            return
        }
        if (!targetFile.exists() || !targetFile.isFile) {
            throw ConcurrentWorkspaceEditException(
                "Conflitto: '${targetFile.name}' è stato rimosso o sostituito esternamente."
            )
        }
        val currentBytes = try {
            readBoundedBytes(targetFile, MAX_EDITABLE_FILE_BYTES)
        } catch (e: IllegalArgumentException) {
            throw ConcurrentWorkspaceEditException(
                "Conflitto: '${targetFile.name}' è stato modificato esternamente oltre il limite consentito."
            )
        }
        if (sha256Hex(currentBytes) != expectedSha256) {
            throw ConcurrentWorkspaceEditException(
                "Conflitto: '${targetFile.name}' è stato modificato esternamente durante l'operazione."
            )
        }
    }

    private fun readBoundedBytes(file: File, maxBytes: Int): ByteArray {
        val len = file.length()
        if (len > maxBytes.toLong()) {
            throw IllegalArgumentException(
                "File '${file.name}' troppo grande ($len byte; limite $maxBytes byte)."
            )
        }
        val out = java.io.ByteArrayOutputStream(len.toInt().coerceAtLeast(0))
        val buf = ByteArray(8192)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (out.size() + n > maxBytes) {
                    throw IllegalArgumentException(
                        "File '${file.name}' supera il limite massimo modificabile ($maxBytes byte)."
                    )
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun replaceFileAtomically(tempFile: File, targetFile: File) {
        if (targetFile.exists() && targetFile.isDirectory) {
            throw java.io.IOException("Impossibile sovrascrivere la directory '${targetFile.name}'.")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                return
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                return
            }
        }
        if (!tempFile.renameTo(targetFile)) {
            throw java.io.IOException("Impossibile sostituire atomicamente '${targetFile.name}'.")
        }
    }

    suspend fun writeFile(
        projectName: String,
        relativePath: String,
        content: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val newBytes = encodeStrictUtf8(content, MAX_EDITABLE_FILE_BYTES)
            val state = inspectFileState(projectName, relativePath, MAX_EDITABLE_FILE_BYTES)
            val previousText = if (state.exists) decodeStrictUtf8(state.bytes!!) else ""
            writeBytesChecked(
                targetFile = state.file,
                newBytes = newBytes,
                expectedBeforeExists = state.exists,
                expectedBeforeSha256 = state.sha256
            )
            previousText
        }
    }

    suspend fun patchFile(
        projectName: String,
        relativePath: String,
        oldText: String,
        newText: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(oldText.isNotEmpty()) { "Il testo target (oldText) non può essere vuoto." }
            encodeStrictUtf8(oldText, MAX_EDITABLE_FILE_BYTES)
            encodeStrictUtf8(newText, MAX_EDITABLE_FILE_BYTES)
            val state = inspectFileState(projectName, relativePath, MAX_EDITABLE_FILE_BYTES)
            if (!state.exists) {
                throw IllegalArgumentException("File inesistente: $relativePath")
            }
            val previous = decodeStrictUtf8(state.bytes!!)
            val firstIdx = previous.indexOf(oldText)
            if (firstIdx < 0) {
                throw IllegalStateException("Testo target non trovato in ${state.normalizedPath}")
            }
            if (previous.lastIndexOf(oldText) != firstIdx) {
                throw IllegalStateException(
                    "Testo target ambiguo in ${state.normalizedPath} (più occorrenze). Specifica più contesto."
                )
            }
            val updated = previous.substring(0, firstIdx) + newText + previous.substring(firstIdx + oldText.length)
            val newBytes = encodeStrictUtf8(updated, MAX_EDITABLE_FILE_BYTES)
            writeBytesChecked(
                targetFile = state.file,
                newBytes = newBytes,
                expectedBeforeExists = true,
                expectedBeforeSha256 = state.sha256
            )
            previous to updated
        }
    }

    suspend fun deleteFile(projectName: String, relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolveSafeFile(projectName, relativePath) ?: return@withContext false
        file.exists() && file.delete()
    }

    suspend fun searchCode(
        projectName: String,
        pattern: String,
        maxResults: Int = 40
    ): List<CodeSearchMatch> = withContext(Dispatchers.IO) {
        val root = projectDir(projectName)
        if (!root.exists() || pattern.isBlank()) return@withContext emptyList()
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .getOrElse { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        val matches = mutableListOf<CodeSearchMatch>()
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(TEMP_FILE_PREFIX) && it.length() < 512_000 }
            .forEach { file ->
                if (matches.size >= maxResults) return@forEach
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (matches.size < maxResults && regex.containsMatchIn(line)) {
                        matches.add(CodeSearchMatch(rel, idx + 1, line.trim().take(160)))
                    }
                }
            }
        matches
    }

    suspend fun buildCompactProjectMap(projectName: String): String = withContext(Dispatchers.IO) {
        val files = listFiles(projectName)
        if (files.isEmpty()) return@withContext "Progetto attivo '$projectName' (vuoto)"
        buildString {
            appendLine("Progetto attivo: `$projectName` (${files.size} file)")
            files.take(25).forEach { f ->
                appendLine("  - ${f.relativePath} (${f.sizeBytes} B)")
            }
        }.trimEnd()
    }

    suspend fun exportProjectAsZip(projectName: String, outputStream: OutputStream) =
        withContext(Dispatchers.IO) {
            val root = projectDir(projectName)
            ZipOutputStream(outputStream).use { zos ->
                root.walkTopDown()
                    .filter { it.isFile && !it.name.startsWith(TEMP_FILE_PREFIX) }
                    .forEach { file ->
                        val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry("$projectName/$rel"))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }

    fun buildPreviewHtml(projectName: String, selectedFile: String? = null): String {
        val root = projectDir(projectName)
        val htmlFile = when {
            selectedFile != null && selectedFile.endsWith(".html", ignoreCase = true) ->
                resolveSafeFile(projectName, selectedFile)
            else -> File(root, "index.html")
        }
        val rawHtml = if (htmlFile != null && htmlFile.exists()) {
            htmlFile.readText(Charsets.UTF_8)
        } else {
            "<html><body style='font-family:sans-serif;padding:20px;background:#0e1412;color:#dfe4e0'><h3>Nessun file index.html nel progetto</h3><p>Chiedi a Gemma 4 di creare un file index.html oppure crealo nell'editor.</p></body></html>"
        }
        // Inline local script tags referencing workspace .js files for clean offline WebView execution
        val scriptRegex = Regex("""<script\s+src=["']([^"']+)["']\s*></script>""", RegexOption.IGNORE_CASE)
        return scriptRegex.replace(rawHtml) { match ->
            val src = match.groupValues[1]
            val jsFile = resolveSafeFile(projectName, src)
            if (jsFile != null && jsFile.exists()) {
                "<script>\n${jsFile.readText(Charsets.UTF_8)}\n</script>"
            } else {
                match.value
            }
        }
    }

    private fun projectDir(projectName: String): File {
        val safe = sanitizeSegment(projectName).ifBlank { "starter_web" }
        return File(workspaceRoot, safe).apply { mkdirs() }
    }

    private fun resolveSafeFileInternal(projectName: String, relativePath: String): Pair<String, File>? {
        val root = projectDir(projectName).canonicalFile
        val clean = relativePath.trim().replace('\\', '/').removePrefix("/")
        if (clean.isBlank() || clean.contains("..")) return null
        val target = File(root, clean).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return null
        val normalized = target.relativeTo(root).path.replace(File.separatorChar, '/')
        if (normalized.isBlank()) return null
        return normalized to target
    }

    private fun resolveSafeFile(projectName: String, relativePath: String): File? =
        resolveSafeFileInternal(projectName, relativePath)?.second

    private fun sanitizeSegment(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40)

    companion object {
        private val STARTER_HTML = """
            <!DOCTYPE html>
            <html lang="it">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>MobHarn Sandbox Demo</title>
              <style>
                body {
                  margin: 0; padding: 20px;
                  font-family: system-ui, -apple-system, sans-serif;
                  background: #0E1412; color: #DFE4E0;
                }
                .card {
                  background: #1A211E; border: 1px solid #344B42;
                  border-radius: 20px; padding: 20px; max-width: 440px; margin: auto;
                }
                h2 { margin-top: 0; color: #7BE0B8; }
                button {
                  background: #7BE0B8; color: #003827; border: none;
                  border-radius: 999px; padding: 10px 18px; font-weight: 700;
                  cursor: pointer; margin-right: 8px;
                }
                #count { font-size: 36px; font-weight: 800; margin: 12px 0; }
              </style>
            </head>
            <body>
              <div class="card">
                <h2>MobHarn Live Preview</h2>
                <p>Modifica questo progetto con Gemma 4 oppure direttamente dall'editor codice.</p>
                <div id="count">0</div>
                <button onclick="step(1)">+1</button>
                <button onclick="step(-1)">-1</button>
                <button onclick="resetCounter()">Reset</button>
              </div>
              <script src="app.js"></script>
            </body>
            </html>
        """.trimIndent()

        private val STARTER_JS = """
            // Script ES6 eseguibile sia nella Live Preview sia nella Console JS Sandbox
            let counter = 0;

            function step(delta) {
              counter += delta;
              const el = typeof document !== 'undefined' ? document.getElementById('count') : null;
              if (el) el.textContent = String(counter);
              return counter;
            }

            function resetCounter() {
              counter = 0;
              return step(0);
            }

            function fibonacci(n) {
              let a = 0, b = 1;
              for (let i = 0; i < n; i++) [a, b] = [b, a + b];
              return a;
            }

            console.log("MobHarn JS Sandbox pronto! fib(10) =", fibonacci(10));
        """.trimIndent()

        private val STARTER_SQL = """
            -- Esempio eseguibile nella Sandbox SQLite in-memory
            CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT, priority INTEGER, done INTEGER);
            INSERT INTO tasks VALUES (1, 'Importa Gemma 4 .litertlm', 5, 0);
            INSERT INTO tasks VALUES (2, 'Configura SOUL.md e USER.md', 4, 1);
            INSERT INTO tasks VALUES (3, 'Testa il Coding Agent offline', 5, 0);
            SELECT id, title, priority FROM tasks WHERE done = 0 ORDER BY priority DESC;
        """.trimIndent()
    }
}
