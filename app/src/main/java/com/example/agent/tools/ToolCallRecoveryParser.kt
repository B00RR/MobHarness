package com.example.agent.tools

import org.json.JSONArray
import org.json.JSONObject

data class ParsedToolCall(
    val name: String,
    val args: Map<String, Any?>
)

/**
 * Forgiving parser that recovers Gemma 4 / LiteRT-LM tool calls when the native C++ ANTLR FC parser
 * throws Status Code 3 (e.g., when quantized Gemma 4 emits standard double quotes `"` instead of
 * `<|"|>` inside `<|tool_call>call:web_search{query:"popolazione Cardito (na)"}<tool_call|>`),
 * when a tool-call block is truncated before closing, or when raw tool-call markup leaks into text.
 */
object ToolCallRecoveryParser {

    private const val GEMMA_QUOTE = "<|\"|>"

    private val TOOL_BLOCK_REGEX = Regex(
        """<\|tool_call>\s*(.*?)\s*<tool_call\|>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    // Supports `call:web_search{`, `call:default_api:web_search{`, `call:default_api.web_search{`
    private val CALL_HEADER_REGEX = Regex(
        """call:(?:[a-zA-Z0-9_]+[:.])*([a-zA-Z0-9_]+)\s*\{"""
    )

    // Also supports `call:web_search(...)` or `web_search(...)` inside tool blocks (quote-aware parens)
    private val PYTHON_CALL_HEADER_REGEX = Regex(
        """(?:call:(?:[a-zA-Z0-9_]+[:.])*)?([a-z][a-z0-9_]{2,})\s*\("""
    )
    private val EXPLICIT_PYTHON_CALL_HEADER_REGEX = Regex(
        """call:(?:[a-zA-Z0-9_]+[:.])*([a-z][a-z0-9_]{2,})\s*\("""
    )

    // Detects the start of the next `key:` or `key=` pair inside an unquoted argument value
    private val NEXT_KEY_SEPARATOR_REGEX = Regex(
        """^,\s*(?:<\|"\|>[a-zA-Z_][a-zA-Z0-9_]*<\|"\|>|["'][a-zA-Z_][a-zA-Z0-9_]*["']|[a-zA-Z_][a-zA-Z0-9_]*)\s*[:=]"""
    )

    fun isToolParseFailure(throwable: Throwable): Boolean {
        val msg = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
        return msg.contains("Failed to parse tool calls", ignoreCase = true) ||
            msg.contains("Failed to parse FC tool calls", ignoreCase = true) ||
            msg.contains("FailThroughParseCancelledError", ignoreCase = true) ||
            msg.contains("<|tool_call>", ignoreCase = true) ||
            msg.contains("Tool not found", ignoreCase = true) ||
            msg.contains("Unknown tool", ignoreCase = true) ||
            msg.contains("Missing required parameter", ignoreCase = true)
    }

    /**
     * Normalizes raw or slightly hallucinated tool names:
     * - Strips namespaces (`default_api:web_search`, `functions.web_search`, `call:web_search`)
     * - Collapses repeated trailing tokens (`web_search_search_search` -> `web_search`)
     * - Trims stray punctuation/quotes
     */
    fun normalizeToolName(rawName: String): String {
        val stripped = rawName
            .trim()
            .trim('"', '\'', '`')
            .substringAfterLast(':')
            .substringAfterLast('.')
            .trim()
            .lowercase()
        if (stripped.isEmpty()) return ""

        val parts = stripped.split('_').filter { it.isNotBlank() }
        if (parts.size <= 1) return stripped

        val deduped = mutableListOf<String>()
        for (part in parts) {
            if (deduped.isEmpty() || deduped.last() != part) {
                deduped.add(part)
            }
        }
        return deduped.joinToString("_")
    }

    fun extractToolCallsFromError(errorMessage: String): List<ParsedToolCall> {
        if (errorMessage.isBlank()) return emptyList()

        // Prefer the "full response:" section if present, otherwise inspect the whole error message
        val fullResponseIdx = errorMessage.indexOf("full response:", ignoreCase = true)
        val candidateRegion = if (fullResponseIdx >= 0) {
            val afterFull = errorMessage.substring(fullResponseIdx + "full response:".length)
            val errIdx = afterFull.indexOf("\nerror:", ignoreCase = true)
            if (errIdx > 0) afterFull.substring(0, errIdx) else afterFull
        } else {
            errorMessage
        }

        val fromFull = extractToolCallsFromText(candidateRegion)
        if (fromFull.isNotEmpty()) return fromFull

        // Fallback: inspect the "from code block:" portion directly
        val codeBlockIdx = errorMessage.indexOf("from code block:", ignoreCase = true)
        if (codeBlockIdx >= 0) {
            val afterBlock = errorMessage.substring(codeBlockIdx + "from code block:".length)
            val stopIdx = listOf(
                afterBlock.indexOf("full response:", ignoreCase = true),
                afterBlock.indexOf("error:", ignoreCase = true)
            ).filter { it > 0 }.minOrNull() ?: afterBlock.length
            val fromBlock = extractToolCallsFromText(afterBlock.substring(0, stopIdx))
            if (fromBlock.isNotEmpty()) return fromBlock
        }

        return extractToolCallsFromText(errorMessage)
    }

    fun extractToolCallsFromText(text: String): List<ParsedToolCall> {
        if (text.isBlank()) return emptyList()
        val results = mutableListOf<ParsedToolCall>()

        // 1. Explicit <|tool_call> ... <tool_call|> blocks + any trailing unclosed <|tool_call>... tail
        val blocks = mutableListOf<String>()
        var lastMatchEnd = 0
        TOOL_BLOCK_REGEX.findAll(text).forEach { m ->
            blocks.add(m.groupValues[1])
            lastMatchEnd = m.range.last + 1
        }
        val danglingOpen = text.indexOf("<|tool_call>", startIndex = lastMatchEnd)
        if (danglingOpen >= 0) {
            val tail = text.substring(danglingOpen + "<|tool_call>".length).trim()
            if (tail.isNotEmpty()) blocks.add(tail)
        }

        val scanTargets = if (blocks.isNotEmpty()) blocks else listOf(text)

        for (target in scanTargets) {
            results.addAll(parseGemmaFcCalls(target))
        }
        if (results.isNotEmpty()) return results

        // 2. JSON object or JSON array tool calls (supports stringified arguments)
        for (target in scanTargets) {
            results.addAll(parseJsonToolCalls(target))
        }
        if (results.isNotEmpty()) return results

        // 3. Python-like function call inside tool blocks (or explicit `call:name(...)` anywhere)
        if (blocks.isNotEmpty()) {
            for (block in blocks) {
                results.addAll(parsePythonStyleCalls(block, explicitCallPrefixOnly = false))
            }
        } else if (text.contains("call:")) {
            results.addAll(parsePythonStyleCalls(text, explicitCallPrefixOnly = true))
        }

        return results
    }

    fun stripRawToolCallMarkup(text: String): String {
        if (text.isBlank()) return text
        var cleaned = TOOL_BLOCK_REGEX.replace(text, "")
        // Strip any truncated/unclosed <|tool_call>... tail
        val danglingIdx = cleaned.indexOf("<|tool_call>")
        if (danglingIdx >= 0) {
            cleaned = cleaned.substring(0, danglingIdx)
        }
        // Strip leaked bare `call:default_api:tool{...}` invocations if they ever appear outside tags
        cleaned = stripBareCallBlocks(cleaned)
        cleaned = cleaned
            .replace("<tool_call|>", "")
            .replace("<|call>", "")
            .replace("<call|>", "")
            .replace("<|tool_response>", "")
            .replace("<tool_response|>", "")
            .replace(GEMMA_QUOTE, "\"")
        return cleaned.trim()
    }

    private fun stripBareCallBlocks(text: String): String {
        if (!text.contains("call:")) return text
        val sb = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            val match = CALL_HEADER_REGEX.find(text, cursor) ?: break
            sb.append(text, cursor, match.range.first)
            val openBrace = match.range.last
            val closeBrace = findMatchingBrace(text, openBrace)
            cursor = if (closeBrace > openBrace) closeBrace + 1 else text.length
        }
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }
        return sb.toString()
    }

    private fun parseGemmaFcCalls(input: String): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()
        var searchFrom = 0
        while (searchFrom < input.length) {
            val match = CALL_HEADER_REGEX.find(input, searchFrom) ?: break
            val toolName = normalizeToolName(match.groupValues[1])
            val openBraceIdx = match.range.last // index of '{'
            val closeBraceIdx = findMatchingBrace(input, openBraceIdx)
            val argsSlice = if (closeBraceIdx > openBraceIdx) {
                input.substring(openBraceIdx + 1, closeBraceIdx)
            } else {
                // Handle truncated call missing closing brace
                input.substring(openBraceIdx + 1).trimEnd('>', '|', ' ', '\n', '\r')
            }
            val parsedArgs = parseKeyValueObjectBody(argsSlice)
            if (toolName.isNotBlank()) {
                calls.add(ParsedToolCall(name = toolName, args = parsedArgs))
            }
            searchFrom = if (closeBraceIdx > openBraceIdx) closeBraceIdx + 1 else input.length
        }
        return calls
    }

    private fun findMatchingBrace(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        var inDouble = false
        var inSingle = false
        var inGemmaQuote = false

        while (i < s.length) {
            if (!inDouble && !inSingle && s.startsWith(GEMMA_QUOTE, i)) {
                inGemmaQuote = !inGemmaQuote
                i += GEMMA_QUOTE.length
                continue
            }
            val ch = s[i]
            if (inGemmaQuote) {
                i++
                continue
            }
            if (inDouble) {
                if (ch == '\\' && i + 1 < s.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inDouble = false
                i++
                continue
            }
            if (inSingle) {
                if (ch == '\\' && i + 1 < s.length) {
                    i += 2
                    continue
                }
                if (ch == '\'') inSingle = false
                i++
                continue
            }

            when (ch) {
                '"' -> inDouble = true
                // Only treat `'` as an opening string quote when NOT an apostrophe inside a word (e.g. cos'è, l'Aquila)
                '\'' -> {
                    val prevIsWord = i > 0 && s[i - 1].isLetterOrDigit()
                    val nextIsWord = i + 1 < s.length && s[i + 1].isLetterOrDigit()
                    if (!(prevIsWord && nextIsWord)) {
                        inSingle = true
                    }
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Parses forgiving key:value pairs inside `{ ... }`:
     * e.g. `query:"popolazione Cardito (na)"`, `hour:7, minute:30, message:<|"|>Sveglia<|"|>`,
     * or even unquoted values with commas/apostrophes like `query:cos'è Cardito, Italia`.
     */
    private fun parseKeyValueObjectBody(body: String): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        var i = 0
        val n = body.length

        fun skipWsAndCommas() {
            while (i < n && (body[i].isWhitespace() || body[i] == ',')) i++
        }

        while (i < n) {
            skipWsAndCommas()
            if (i >= n) break

            // Read key (possibly quoted with ", ', or <|"|>)
            val (rawKey, afterKey) = readKey(body, i)
            val key = rawKey.trim()
            i = afterKey
            if (key.isEmpty()) {
                i++
                continue
            }

            // Skip whitespace and ':' or '=' separator
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n || (body[i] != ':' && body[i] != '=')) {
                // Skip stray token cleanly instead of losing all subsequent parameters
                while (i < n && body[i] != ',') i++
                continue
            }
            i++ // consume ':' or '='
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n) break

            // Read value
            val (value, nextIdx) = readValue(body, i)
            i = nextIdx
            result[key] = value
        }
        return result
    }

    private fun readKey(s: String, start: Int): Pair<String, Int> {
        if (s.startsWith(GEMMA_QUOTE, start)) {
            return readGemmaQuotedString(s, start)
        }
        if (s[start] == '"' || s[start] == '\'') {
            return readQuotedString(s, start, s[start])
        }
        var i = start
        while (i < s.length && s[i] != ':' && s[i] != '=' && s[i] != ',' && !s[i].isWhitespace()) {
            i++
        }
        return s.substring(start, i) to i
    }

    private fun readValue(s: String, start: Int): Pair<Any?, Int> {
        val n = s.length
        if (start >= n) return "" to start

        if (s.startsWith(GEMMA_QUOTE, start)) {
            return readGemmaQuotedString(s, start)
        }
        return when (val ch = s[start]) {
            '"', '\'' -> readQuotedString(s, start, ch)
            '{' -> {
                val end = findMatchingBrace(s, start)
                if (end > start) {
                    val inner = s.substring(start + 1, end)
                    parseKeyValueObjectBody(inner) to (end + 1)
                } else {
                    s.substring(start) to n
                }
            }
            '[' -> {
                val end = findMatchingBracket(s, start)
                val slice = if (end > start) s.substring(start, end + 1) else s.substring(start)
                val parsed = runCatching {
                    val arr = JSONArray(slice.replace(GEMMA_QUOTE, "\""))
                    (0 until arr.length()).map { arr.opt(it) }
                }.getOrDefault(slice)
                parsed to if (end > start) end + 1 else n
            }
            else -> {
                // Unquoted value: only stop at a comma if it starts a new `key:`/`key=` pair!
                // This preserves natural text with commas like `query:Cardito, Napoli` or `url:https://a.it`.
                var i = start
                while (i < n && s[i] != '}') {
                    if (s[i] == ',' && NEXT_KEY_SEPARATOR_REGEX.containsMatchIn(s.substring(i))) {
                        break
                    }
                    i++
                }
                val raw = s.substring(start, i).trim()
                parseScalar(raw) to i
            }
        }
    }

    private fun readGemmaQuotedString(s: String, start: Int): Pair<String, Int> {
        val contentStart = start + GEMMA_QUOTE.length
        val end = s.indexOf(GEMMA_QUOTE, contentStart)
        return if (end >= 0) {
            s.substring(contentStart, end) to (end + GEMMA_QUOTE.length)
        } else {
            s.substring(contentStart) to s.length
        }
    }

    private fun readQuotedString(s: String, start: Int, quoteChar: Char): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    else -> sb.append(next)
                }
                i += 2
                continue
            }
            if (ch == quoteChar) {
                return sb.toString() to (i + 1)
            }
            sb.append(ch)
            i++
        }
        return sb.toString() to s.length
    }

    private fun findMatchingBracket(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        var inDouble = false
        var inSingle = false
        while (i < s.length) {
            val ch = s[i]
            if (inDouble) {
                if (ch == '\\' && i + 1 < s.length) { i += 2; continue }
                if (ch == '"') inDouble = false
                i++; continue
            }
            if (inSingle) {
                if (ch == '\\' && i + 1 < s.length) { i += 2; continue }
                if (ch == '\'') inSingle = false
                i++; continue
            }
            when (ch) {
                '"' -> inDouble = true
                '\'' -> inSingle = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun parseScalar(raw: String): Any? = when {
        raw.equals("true", ignoreCase = true) -> true
        raw.equals("false", ignoreCase = true) -> false
        raw.equals("null", ignoreCase = true) -> null
        raw.toLongOrNull() != null -> raw.toLong()
        raw.toDoubleOrNull() != null -> raw.toDouble()
        else -> raw
    }

    private fun parseJsonToolCalls(text: String): List<ParsedToolCall> {
        val out = mutableListOf<ParsedToolCall>()
        val normalizedText = text.replace(GEMMA_QUOTE, "\"")

        fun parseSingleObject(obj: JSONObject): ParsedToolCall? {
            val rawName = obj.optString("name").ifBlank { obj.optString("tool") }.trim()
            val name = normalizeToolName(rawName)
            if (name.isBlank()) return null

            val argsObj: JSONObject? = when {
                obj.optJSONObject("arguments") != null -> obj.optJSONObject("arguments")
                obj.optJSONObject("parameters") != null -> obj.optJSONObject("parameters")
                obj.optJSONObject("args") != null -> obj.optJSONObject("args")
                else -> {
                    // Handle stringified JSON arguments: {"name":"web_search","arguments":"{\"query\":\"Cardito\"}"}
                    val rawStr = obj.optString("arguments")
                        .ifBlank { obj.optString("parameters") }
                        .ifBlank { obj.optString("args") }
                        .trim()
                    if (rawStr.startsWith("{")) runCatching { JSONObject(rawStr) }.getOrNull() else null
                }
            }

            val argsMap = linkedMapOf<String, Any?>()
            if (argsObj != null) {
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = argsObj.opt(k)
                    argsMap[k] = if (v == JSONObject.NULL) null else v
                }
            }
            return ParsedToolCall(name = name, args = argsMap)
        }

        // First try top-level JSON array `[{"name": ...}, ...]`
        val trimmed = normalizedText.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            runCatching {
                val arr = JSONArray(trimmed)
                for (idx in 0 until arr.length()) {
                    arr.optJSONObject(idx)?.let { parseSingleObject(it) }?.let { out.add(it) }
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Scan all balanced `{ ... }` blocks in the text (not just the first one)
        var cursor = 0
        while (cursor < normalizedText.length) {
            val start = normalizedText.indexOf('{', cursor)
            if (start < 0) break
            val end = findMatchingBrace(normalizedText, start)
            if (end <= start) break
            val slice = normalizedText.substring(start, end + 1)
            runCatching {
                val obj = JSONObject(slice)
                parseSingleObject(obj)?.let { out.add(it) }
            }
            cursor = end + 1
        }
        return out
    }

    private fun parsePythonStyleCalls(block: String, explicitCallPrefixOnly: Boolean): List<ParsedToolCall> {
        val regex = if (explicitCallPrefixOnly) EXPLICIT_PYTHON_CALL_HEADER_REGEX else PYTHON_CALL_HEADER_REGEX
        val calls = mutableListOf<ParsedToolCall>()
        var cursor = 0
        while (cursor < block.length) {
            val m = regex.find(block, cursor) ?: break
            val fnName = normalizeToolName(m.groupValues[1])
            val openParenIdx = m.range.last
            val closeParenIdx = findMatchingParen(block, openParenIdx)
            val argsText = if (closeParenIdx > openParenIdx) {
                block.substring(openParenIdx + 1, closeParenIdx).trim()
            } else {
                block.substring(openParenIdx + 1).trimEnd('>', '|', ' ', '\n', '\r').trim()
            }
            if (fnName.isNotBlank()) {
                val args = if (argsText.isEmpty()) {
                    emptyMap()
                } else if (argsText.contains('=') || argsText.contains(':')) {
                    parseKeyValueObjectBody(argsText)
                } else {
                    val cleaned = argsText.trim()
                        .removeSurrounding(GEMMA_QUOTE)
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    mapOf("arg0" to cleaned, "query" to cleaned, "appName" to cleaned)
                }
                calls.add(ParsedToolCall(name = fnName, args = args))
            }
            cursor = if (closeParenIdx > openParenIdx) closeParenIdx + 1 else block.length
        }
        return calls
    }

    private fun findMatchingParen(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        var inDouble = false
        var inSingle = false
        var inGemmaQuote = false

        while (i < s.length) {
            if (!inDouble && !inSingle && s.startsWith(GEMMA_QUOTE, i)) {
                inGemmaQuote = !inGemmaQuote
                i += GEMMA_QUOTE.length
                continue
            }
            val ch = s[i]
            if (inGemmaQuote) {
                i++
                continue
            }
            if (inDouble) {
                if (ch == '\\' && i + 1 < s.length) { i += 2; continue }
                if (ch == '"') inDouble = false
                i++; continue
            }
            if (inSingle) {
                if (ch == '\\' && i + 1 < s.length) { i += 2; continue }
                if (ch == '\'') inSingle = false
                i++; continue
            }
            when (ch) {
                '"' -> inDouble = true
                '\'' -> {
                    val prevIsWord = i > 0 && s[i - 1].isLetterOrDigit()
                    val nextIsWord = i + 1 < s.length && s[i + 1].isLetterOrDigit()
                    if (!(prevIsWord && nextIsWord)) inSingle = true
                }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
