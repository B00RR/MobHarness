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

internal fun readExcerpt(input: java.io.InputStream, size: Long, start: Int, end: Int, path: String): String {
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
    val prefix = bytes.toString("UTF-8")
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
        val note = if (more || start > 1 || rows.hasNext()) " (lettura parziale)" else ""
        "File: $path$note" + content.ifEmpty { "\nNessuna riga completa nell'intervallo letto." }
    }
}

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
            .filter { it.isFile }
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
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = requireNotNull(resolveSafeFile(projectName, relativePath)?.takeIf { it.isFile }) {
                "File non trovato: $relativePath"
            }
            file.inputStream().use { input -> readExcerpt(input, input.channel.size(), startLine, endLine, relativePath) }
        }
    }

    suspend fun writeFile(
        projectName: String,
        relativePath: String,
        content: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val file = resolveSafeFile(projectName, relativePath)
            ?: return@withContext Result.failure(SecurityException("Percorso non valido: $relativePath"))
        file.parentFile?.mkdirs()
        val previous = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        file.writeText(content, Charsets.UTF_8)
        Result.success(previous)
    }

    suspend fun patchFile(
        projectName: String,
        relativePath: String,
        oldText: String,
        newText: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val file = resolveSafeFile(projectName, relativePath)
            ?: return@withContext Result.failure(SecurityException("Percorso non valido: $relativePath"))
        if (!file.exists()) {
            return@withContext Result.failure(IllegalArgumentException("File inesistente: $relativePath"))
        }
        val previous = file.readText(Charsets.UTF_8)
        if (!previous.contains(oldText)) {
            return@withContext Result.failure(IllegalStateException("Testo target non trovato in $relativePath"))
        }
        val updated = previous.replaceFirst(oldText, newText)
        file.writeText(updated, Charsets.UTF_8)
        Result.success(previous to updated)
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
            .filter { it.isFile && it.length() < 512_000 }
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
                    .filter { it.isFile }
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

    private fun resolveSafeFile(projectName: String, relativePath: String): File? {
        val root = projectDir(projectName).canonicalFile
        val clean = relativePath.trim().removePrefix("/")
        if (clean.isBlank() || clean.contains("..")) return null
        val target = File(root, clean).canonicalFile
        return if (target.path.startsWith(root.path + File.separator) || target == root) target else null
    }

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
