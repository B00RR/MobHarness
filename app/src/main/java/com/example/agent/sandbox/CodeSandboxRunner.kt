package com.example.agent.sandbox

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class SandboxExecutionResult(
    val success: Boolean,
    val output: String,
    val durationMs: Long,
    val engineLabel: String
)

class CodeSandboxRunner(private val context: Context) {

    /**
     * Executes JavaScript (ES6+) offline inside an isolated offscreen WebView with
     * file/content access disabled, capturing console output, return value, and exceptions.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runJavaScript(code: String, timeoutMs: Long = 5000L): SandboxExecutionResult {
        val start = System.currentTimeMillis()
        if (code.isBlank()) {
            return SandboxExecutionResult(false, "Nessun codice JavaScript fornito.", 0, "JS ES6+ Sandbox")
        }

        val deferred = CompletableDeferred<Pair<Boolean, String>>()

        return withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.blockNetworkLoads = true
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onComplete(ok: Boolean, payload: String) {
                        if (!deferred.isCompleted) {
                            deferred.complete(ok to payload)
                        }
                    }
                }
                webView.addJavascriptInterface(bridge, "MobHarnBridge")

                val encoded = JSONObject.quote(code)
                val harnessHtml = """
                    <!DOCTYPE html>
                    <html><head><meta charset="utf-8"></head><body>
                    <script>
                    (function() {
                      const logs = [];
                      const fmt = (v) => {
                        if (v === undefined) return 'undefined';
                        if (v === null) return 'null';
                        if (typeof v === 'object') {
                          try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
                        }
                        return String(v);
                      };
                      console.log = (...a) => logs.push(a.map(fmt).join(' '));
                      console.info = (...a) => logs.push('[INFO] ' + a.map(fmt).join(' '));
                      console.warn = (...a) => logs.push('[WARN] ' + a.map(fmt).join(' '));
                      console.error = (...a) => logs.push('[ERROR] ' + a.map(fmt).join(' '));

                      try {
                        const userCode = $encoded;
                        const fn = new Function(userCode);
                        const res = fn();
                        if (res !== undefined) {
                          logs.push('=> ' + fmt(res));
                        }
                        if (logs.length === 0) {
                          logs.push('(eseguito senza output in console)');
                        }
                        MobHarnBridge.onComplete(true, logs.join('\n'));
                      } catch (err) {
                        logs.push('Exception: ' + (err && err.stack ? err.stack : String(err)));
                        MobHarnBridge.onComplete(false, logs.join('\n'));
                      }
                    })();
                    </script>
                    </body></html>
                """.trimIndent()

                webView.loadDataWithBaseURL(
                    "https://sandbox.mobharn.local/",
                    harnessHtml,
                    "text/html",
                    "UTF-8",
                    null
                )

                val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() }
                val elapsed = System.currentTimeMillis() - start
                if (outcome == null) {
                    SandboxExecutionResult(
                        success = false,
                        output = "Timeout esecuzione JS (${timeoutMs}ms): possibile loop infinito.",
                        durationMs = elapsed,
                        engineLabel = "JS ES6+ Sandbox"
                    )
                } else {
                    SandboxExecutionResult(
                        success = outcome.first,
                        output = outcome.second.take(8000),
                        durationMs = elapsed,
                        engineLabel = "JS ES6+ Sandbox"
                    )
                }
            } catch (t: Throwable) {
                SandboxExecutionResult(
                    success = false,
                    output = "Errore inizializzazione WebView JS Sandbox: ${t.message}",
                    durationMs = System.currentTimeMillis() - start,
                    engineLabel = "JS ES6+ Sandbox"
                )
            } finally {
                runCatching {
                    webView?.stopLoading()
                    webView?.removeJavascriptInterface("MobHarnBridge")
                    webView?.clearCache(true)
                    webView?.clearHistory()
                    webView?.destroy()
                }
            }
        }
    }

    /**
     * Executes SQL DDL/DML/SELECT scripts against a fresh in-memory SQLite database.
     */
    suspend fun runSql(sqlScript: String): SandboxExecutionResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        if (sqlScript.isBlank()) {
            return@withContext SandboxExecutionResult(false, "Nessuna query SQL fornita.", 0, "SQLite Sandbox")
        }
        val db = SQLiteDatabase.create(null)
        try {
            val statements = splitSqlStatements(sqlScript)

            val sb = StringBuilder()
            statements.forEachIndexed { idx, stmt ->
                val upper = extractEffectiveCommand(stmt)
                if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("WITH") || upper.startsWith("EXPLAIN")) {
                    db.rawQuery(stmt, null).use { cursor ->
                        val cols = cursor.columnNames
                        sb.appendLine("| ${cols.joinToString(" | ")} |")
                        sb.appendLine("| ${cols.joinToString(" | ") { "---" }} |")
                        var rowCount = 0
                        while (cursor.moveToNext() && rowCount < 100) {
                            val row = cols.indices.map { i -> cursor.getString(i) ?: "NULL" }
                            sb.appendLine("| ${row.joinToString(" | ")} |")
                            rowCount++
                        }
                        sb.appendLine("($rowCount righe restituite)")
                    }
                } else {
                    db.execSQL(stmt)
                    sb.appendLine("OK [${idx + 1}]: ${stmt.take(64)}${if (stmt.length > 64) "..." else ""}")
                }
            }
            SandboxExecutionResult(
                success = true,
                output = sb.toString().trim().ifBlank { "Script SQL eseguito." },
                durationMs = System.currentTimeMillis() - start,
                engineLabel = "SQLite In-Memory"
            )
        } catch (t: Throwable) {
            SandboxExecutionResult(
                success = false,
                output = "Errore SQL: ${t.message}",
                durationMs = System.currentTimeMillis() - start,
                engineLabel = "SQLite In-Memory"
            )
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * Pure Kotlin safe recursive-descent mathematical expression evaluator.
     * Supports Unicode operators (×, ÷, −, ², ³, √), Italian decimal commas, and trailing whitespace.
     */
    fun evaluateMath(expression: String): SandboxExecutionResult {
        val start = System.currentTimeMillis()
        val normalized = normalizeMathExpression(expression)
        if (normalized.isBlank()) {
            return SandboxExecutionResult(false, "Espressione matematica vuota.", 0, "Kotlin Math Engine")
        }
        return try {
            val value = MathParser(normalized).parse()
            val formatted = if (value % 1.0 == 0.0 && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
            }
            SandboxExecutionResult(
                success = true,
                output = "${expression.trim()} = $formatted",
                durationMs = System.currentTimeMillis() - start,
                engineLabel = "Kotlin Math Engine"
            )
        } catch (t: Throwable) {
            SandboxExecutionResult(
                success = false,
                output = "Espressione non valida: ${t.message}",
                durationMs = System.currentTimeMillis() - start,
                engineLabel = "Kotlin Math Engine"
            )
        }
    }

    private fun normalizeMathExpression(raw: String): String {
        var s = raw.trim()
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace('–', '-')
            .replace("²", "^2")
            .replace("³", "^3")
            .replace("√", "sqrt")

        // Convert Italian decimal comma `12,5` -> `12.5` only when NOT separating function arguments (e.g. `pow(2, 3)`)
        val out = StringBuilder(s.length)
        val inMultiArgFunc = ArrayDeque<Boolean>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '(' -> {
                    val prefix = s.substring(0, i).trimEnd().lowercase()
                    val isMulti = prefix.endsWith("pow") || prefix.endsWith("min") || prefix.endsWith("max")
                    inMultiArgFunc.addLast(isMulti)
                    out.append(c)
                }
                ')' -> {
                    if (inMultiArgFunc.isNotEmpty()) inMultiArgFunc.removeLast()
                    out.append(c)
                }
                ',' -> {
                    val prevDigit = i > 0 && s[i - 1].isDigit()
                    val nextDigit = i + 1 < s.length && s[i + 1].isDigit()
                    val insideMulti = inMultiArgFunc.lastOrNull() == true
                    if (prevDigit && nextDigit && !insideMulti) {
                        out.append('.')
                    } else {
                        out.append(',')
                    }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString().trim()
    }

    /**
     * Validates/formats JSON or executes Regex extraction on input text.
     */
    fun validateJsonOrRegex(mode: String, input: String, pattern: String = ""): SandboxExecutionResult {
        val start = System.currentTimeMillis()
        return try {
            if (mode.equals("regex", ignoreCase = true)) {
                val regex = Regex(pattern)
                val matches = regex.findAll(input).take(50).toList()
                val text = if (matches.isEmpty()) {
                    "Nessun match trovato per /$pattern/"
                } else {
                    buildString {
                        appendLine("Trovati ${matches.size} match:")
                        matches.forEachIndexed { idx, m ->
                            appendLine("${idx + 1}. `${m.value}` (range ${m.range.first}..${m.range.last})")
                        }
                    }.trimEnd()
                }
                SandboxExecutionResult(true, text, System.currentTimeMillis() - start, "Regex Tester")
            } else {
                val parsed = JSONTokener(input.trim()).nextValue()
                val pretty = when (parsed) {
                    is JSONObject -> parsed.toString(2)
                    is JSONArray -> parsed.toString(2)
                    else -> parsed.toString()
                }
                SandboxExecutionResult(
                    true,
                    "JSON valido:\n$pretty",
                    System.currentTimeMillis() - start,
                    "JSON Validator"
                )
            }
        } catch (t: Throwable) {
            SandboxExecutionResult(
                false,
                "Errore validazione ($mode): ${t.message}",
                System.currentTimeMillis() - start,
                mode.uppercase()
            )
        }
    }

    private class MathParser(private val str: String) {
        private var pos = -1
        private var ch = 0

        private fun nextChar() {
            ch = if (++pos < str.length) str[pos].code else -1
        }

        private fun skipWhitespace() {
            while (ch == ' '.code || ch == '\t'.code || ch == '\n'.code || ch == '\r'.code) nextChar()
        }

        private fun eat(charToEat: Int): Boolean {
            skipWhitespace()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            skipWhitespace()
            if (pos < str.length) throw IllegalArgumentException("Carattere inatteso: '${ch.toChar()}'")
            return x
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                x = when {
                    eat('+'.code) -> x + parseTerm()
                    eat('-'.code) -> x - parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                x = when {
                    eat('*'.code) -> x * parseFactor()
                    eat('/'.code) -> {
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("Divisione per zero")
                        x / d
                    }
                    eat('%'.code) -> x % parseFactor()
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()
            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) throw IllegalArgumentException("Parentesi ')' mancante")
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = str.substring(startPos, pos).toDouble()
            } else if (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) {
                while (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code || ch in '0'.code..'9'.code) nextChar()
                val ident = str.substring(startPos, pos).lowercase()
                x = when (ident) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> {
                        if (!eat('('.code)) throw IllegalArgumentException("Funzione sconosciuta: $ident")
                        val firstArg = parseExpression()
                        val secondArg = if (eat(','.code)) parseExpression() else null
                        if (!eat(')'.code)) throw IllegalArgumentException("Parentesi ')' mancante dopo $ident")
                        when (ident) {
                            "sqrt" -> sqrt(firstArg)
                            "sin" -> sin(Math.toRadians(firstArg))
                            "cos" -> cos(Math.toRadians(firstArg))
                            "tan" -> tan(Math.toRadians(firstArg))
                            "abs" -> abs(firstArg)
                            "ln" -> ln(firstArg)
                            "log", "log10" -> log10(firstArg)
                            "pow" -> firstArg.pow(secondArg ?: 2.0)
                            "min" -> min(firstArg, secondArg ?: firstArg)
                            "max" -> max(firstArg, secondArg ?: firstArg)
                            else -> throw IllegalArgumentException("Funzione non supportata: $ident")
                        }
                    }
                }
            } else {
                throw IllegalArgumentException("Token non valido in posizione $pos")
            }
            if (eat('^'.code)) x = x.pow(parseFactor())
            return x
        }
    }

    companion object {
        /**
         * Splits a SQL script into individual executable statements, honoring:
         * - Single-quoted strings (including escaped '' quotes and semicolons inside quotes)
         * - Double-quoted identifiers (including escaped "" quotes)
         * - Backtick-quoted identifiers (`)
         * - Bracketed identifiers ([...])
         * - Line comments (-- to newline)
         * - Block comments (/* ... */)
         * - Trigger bodies (CREATE TRIGGER ... BEGIN ... END;) where internal semicolons are preserved
         */
        fun splitSqlStatements(sqlScript: String): List<String> {
            if (sqlScript.isBlank()) return emptyList()

            val statements = mutableListOf<String>()
            val currentStmt = StringBuilder()
            var i = 0
            val n = sqlScript.length

            var inSingleQuote = false
            var inDoubleQuote = false
            var inBacktick = false
            var inBracket = false
            var inLineComment = false
            var inBlockComment = false

            var inCreateTrigger = false
            var triggerBeginDepth = 0
            var caseDepth = 0

            fun getWordAt(index: Int): String {
                var end = index
                while (end < n && (sqlScript[end].isLetterOrDigit() || sqlScript[end] == '_')) {
                    end++
                }
                return sqlScript.substring(index, end)
            }

            while (i < n) {
                val c = sqlScript[i]
                val nextC = if (i + 1 < n) sqlScript[i + 1] else null

                if (inSingleQuote) {
                    currentStmt.append(c)
                    if (c == '\'') {
                        if (nextC == '\'') {
                            currentStmt.append(nextC)
                            i += 2
                            continue
                        } else {
                            inSingleQuote = false
                        }
                    }
                    i++
                    continue
                }

                if (inDoubleQuote) {
                    currentStmt.append(c)
                    if (c == '"') {
                        if (nextC == '"') {
                            currentStmt.append(nextC)
                            i += 2
                            continue
                        } else {
                            inDoubleQuote = false
                        }
                    }
                    i++
                    continue
                }

                if (inBacktick) {
                    currentStmt.append(c)
                    if (c == '`') {
                        inBacktick = false
                    }
                    i++
                    continue
                }

                if (inBracket) {
                    currentStmt.append(c)
                    if (c == ']') {
                        inBracket = false
                    }
                    i++
                    continue
                }

                if (inLineComment) {
                    currentStmt.append(c)
                    if (c == '\n') {
                        inLineComment = false
                    }
                    i++
                    continue
                }

                if (inBlockComment) {
                    currentStmt.append(c)
                    if (c == '*' && nextC == '/') {
                        currentStmt.append(nextC)
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }

                // Check starting comments
                if (c == '-' && nextC == '-') {
                    inLineComment = true
                    currentStmt.append(c).append(nextC)
                    i += 2
                    continue
                }

                if (c == '/' && nextC == '*') {
                    inBlockComment = true
                    currentStmt.append(c).append(nextC)
                    i += 2
                    continue
                }

                // Check starting quotes
                if (c == '\'') {
                    inSingleQuote = true
                    currentStmt.append(c)
                    i++
                    continue
                }
                if (c == '"') {
                    inDoubleQuote = true
                    currentStmt.append(c)
                    i++
                    continue
                }
                if (c == '`') {
                    inBacktick = true
                    currentStmt.append(c)
                    i++
                    continue
                }
                if (c == '[') {
                    inBracket = true
                    currentStmt.append(c)
                    i++
                    continue
                }

                // Check keywords outside quotes & comments
                if (c.isLetter() || c == '_') {
                    val word = getWordAt(i)
                    val upper = word.uppercase()
                    if (upper == "TRIGGER") {
                        val soFar = currentStmt.toString().uppercase()
                        if (Regex("""\bCREATE\b""").containsMatchIn(soFar)) {
                            inCreateTrigger = true
                        }
                    } else if (upper == "BEGIN") {
                        if (inCreateTrigger) {
                            triggerBeginDepth++
                        }
                    } else if (upper == "CASE") {
                        caseDepth++
                    } else if (upper == "END") {
                        if (caseDepth > 0) {
                            caseDepth--
                        } else if (triggerBeginDepth > 0) {
                            triggerBeginDepth--
                            if (triggerBeginDepth == 0) {
                                inCreateTrigger = false
                            }
                        }
                    }
                    currentStmt.append(word)
                    i += word.length
                    continue
                }

                // Semicolon handling
                if (c == ';') {
                    if (triggerBeginDepth == 0) {
                        val stmt = currentStmt.toString().trim()
                        if (stmt.isNotBlank() && !isOnlyComments(stmt)) {
                            statements.add(stmt)
                        }
                        currentStmt.clear()
                        inCreateTrigger = false
                        caseDepth = 0
                        i++
                        continue
                    } else {
                        currentStmt.append(c)
                        i++
                        continue
                    }
                }

                currentStmt.append(c)
                i++
            }

            val finalStmt = currentStmt.toString().trim()
            if (finalStmt.isNotBlank() && !isOnlyComments(finalStmt)) {
                statements.add(finalStmt)
            }

            return statements
        }

        private fun isOnlyComments(stmt: String): Boolean {
            var j = 0
            val len = stmt.length
            var inLine = false
            var inBlock = false
            while (j < len) {
                val ch = stmt[j]
                val next = if (j + 1 < len) stmt[j + 1] else null
                if (inLine) {
                    if (ch == '\n') inLine = false
                    j++
                    continue
                }
                if (inBlock) {
                    if (ch == '*' && next == '/') {
                        inBlock = false
                        j += 2
                        continue
                    }
                    j++
                    continue
                }
                if (ch.isWhitespace()) {
                    j++
                    continue
                }
                if (ch == '-' && next == '-') {
                    inLine = true
                    j += 2
                    continue
                }
                if (ch == '/' && next == '*') {
                    inBlock = true
                    j += 2
                    continue
                }
                return false
            }
            return true
        }

        private fun extractEffectiveCommand(stmt: String): String {
            var i = 0
            val len = stmt.length
            var inLine = false
            var inBlock = false
            while (i < len) {
                val c = stmt[i]
                val next = if (i + 1 < len) stmt[i + 1] else null
                if (inLine) {
                    if (c == '\n') inLine = false
                    i++
                    continue
                }
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (c == '-' && next == '-') {
                    inLine = true
                    i += 2
                    continue
                }
                if (c == '/' && next == '*') {
                    inBlock = true
                    i += 2
                    continue
                }
                if (!c.isWhitespace()) {
                    var end = i
                    while (end < len && (stmt[end].isLetterOrDigit() || stmt[end] == '_')) {
                        end++
                    }
                    return stmt.substring(i, end).uppercase()
                }
                i++
            }
            return ""
        }
    }
}
