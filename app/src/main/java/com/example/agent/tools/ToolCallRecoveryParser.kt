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
 * or when raw tool-call markup leaks into regular response text.
 */
object ToolCallRecoveryParser {

    private const val GEMMA_QUOTE = "<|\"|>"

    private val TOOL_BLOCK_REGEX = Regex(
        """<\|tool_call>\s*(.*?)\s*<tool_call\|>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val CALL_HEADER_REGEX = Regex(
        """call:(?:[a-zA-Z0-9_]+:)*([a-zA-Z0-9_]+)\s*\{"""
    )

    private val PYTHON_CALL_REGEX = Regex(
        """\b([a-z][a-z0-9_]{2,})\s*\(([^()\n]{0,600})\)"""
    )

    fun isToolParseFailure(throwable: Throwable): Boolean {
        val msg = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
        return msg.contains("Failed to parse tool calls", ignoreCase = true) ||
            msg.contains("Failed to parse FC tool calls", ignoreCase = true) ||
            msg.contains("FailThroughParseCancelledError", ignoreCase = true) ||
            msg.contains("<|tool_call>", ignoreCase = true)
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
            return extractToolCallsFromText(afterBlock.substring(0, stopIdx))
        }

        return extractToolCallsFromText(errorMessage)
    }

    fun extractToolCallsFromText(text: String): List<ParsedToolCall> {
        if (text.isBlank()) return emptyList()
        val results = mutableListOf<ParsedToolCall>()

        // 1. Explicit <|tool_call> ... <tool_call|> blocks
        val blocks = TOOL_BLOCK_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        val scanTargets = if (blocks.isNotEmpty()) blocks else listOf(text)

        for (target in scanTargets) {
            results.addAll(parseGemmaFcCalls(target))
        }
        if (results.isNotEmpty()) return results

        // 2. JSON object tool call: {"name": "web_search", "arguments": {"query": "..."}}
        parseJsonToolCalls(text).takeIf { it.isNotEmpty() }?.let { return it }

        // 3. Python-like function call inside tool blocks only (avoid false positives on normal prose)
        if (blocks.isNotEmpty()) {
            for (block in blocks) {
                parsePythonStyleCall(block)?.let { results.add(it) }
            }
        }

        return results
    }

    fun stripRawToolCallMarkup(text: String): String {
        if (text.isBlank()) return text
        var cleaned = TOOL_BLOCK_REGEX.replace(text, "")
        // Also strip any truncated/unclosed <|tool_call>... tail
        val danglingIdx = cleaned.indexOf("<|tool_call>")
        if (danglingIdx >= 0) {
            cleaned = cleaned.substring(0, danglingIdx)
        }
        cleaned = cleaned
            .replace("<|tool_response>", "")
            .replace("<tool_response|>", "")
            .replace(GEMMA_QUOTE, "\"")
        return cleaned.trim()
    }

    private fun parseGemmaFcCalls(input: String): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()
        var searchFrom = 0
        while (searchFrom < input.length) {
            val match = CALL_HEADER_REGEX.find(input, searchFrom) ?: break
            val toolName = match.groupValues[1]
            val openBraceIdx = match.range.last // index of '{'
            val closeBraceIdx = findMatchingBrace(input, openBraceIdx)
            val argsSlice = if (closeBraceIdx > openBraceIdx) {
                input.substring(openBraceIdx + 1, closeBraceIdx)
            } else {
                // Handle truncated call missing closing brace
                input.substring(openBraceIdx + 1).trimEnd('>', '|', ' ', '\n', '\r')
            }
            val parsedArgs = parseKeyValueObjectBody(argsSlice)
            calls.add(ParsedToolCall(name = toolName, args = parsedArgs))
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
                '\'' -> inSingle = true
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
     * e.g. `query:"popolazione Cardito (na)"`, `hour:7, minute:30, message:<|"|>Sveglia<|"|>`
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
            val key = readKey(body, i).let { (k, nextIdx) ->
                i = nextIdx
                k.trim()
            }
            if (key.isEmpty()) {
                i++
                continue
            }

            // Skip whitespace and ':' or '=' separator
            while (i < n && body[i].isWhitespace()) i++
            if (i >= n || (body[i] != ':' && body[i] != '=')) break
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
                var i = start
                while (i < n && s[i] != ',' && s[i] != '}') i++
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
        val trimmed = text.trim()
        val jsonCandidates = mutableListOf<String>()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            jsonCandidates.add(trimmed)
        } else {
            val start = text.indexOf('{')
            if (start >= 0) {
                val end = findMatchingBrace(text, start)
                if (end > start) jsonCandidates.add(text.substring(start, end + 1))
            }
        }
        for (cand in jsonCandidates) {
            runCatching {
                val obj = JSONObject(cand.replace(GEMMA_QUOTE, "\""))
                val name = obj.optString("name").ifBlank { obj.optString("tool") }.trim()
                if (name.isNotBlank()) {
                    val argsObj = obj.optJSONObject("arguments") ?: obj.optJSONObject("parameters") ?: obj.optJSONObject("args")
                    val argsMap = linkedMapOf<String, Any?>()
                    if (argsObj != null) {
                        val keys = argsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            argsMap[k] = argsObj.opt(k)
                        }
                    }
                    out.add(ParsedToolCall(name = name, args = argsMap))
                }
            }
        }
        return out
    }

    private fun parsePythonStyleCall(block: String): ParsedToolCall? {
        val m = PYTHON_CALL_REGEX.find(block.trim()) ?: return null
        val fnName = m.groupValues[1]
        val argsText = m.groupValues[2].trim()
        val args = if (argsText.isEmpty()) {
            emptyMap()
        } else if (argsText.contains('=') || argsText.contains(':')) {
            parseKeyValueObjectBody(argsText)
        } else {
            // Single positional string arg
            val cleaned = argsText.trim().removeSurrounding("\"").removeSurrounding("'")
            mapOf("arg0" to cleaned, "query" to cleaned, "appName" to cleaned)
        }
        return ParsedToolCall(name = fnName, args = args)
    }
}
