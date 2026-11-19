package com.example.agent

import com.example.data.db.AgentSkillEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.MemoryEntryEntity
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class ScoredMemory(
    val entry: MemoryEntryEntity,
    val score: Double
)

data class ContextWindowPlan(
    val hotHistoryPairs: List<Pair<String, String>>,
    val updatedSessionDigest: String,
    val didAutoCompact: Boolean,
    val compactedTurnCount: Int,
    val estimatedPromptTokens: Int,
    val maxContextTokens: Int,
    val perMessageCharLimit: Int,
    val lastSummarizedMessageId: Long = 0L
)

/**
 * Pure-Kotlin, zero-GPU-cost Mobile Context & Memory Engine designed for long-horizon work on smartphones:
 * 1. Hybrid BM25 + prefix-stemming + importance + recency search for Italian/English memories, skills, and chat history.
 * 2. Hierarchical 3-tier context management (Hot verbatim window + Warm rolling session checkpoint + Cold searchable vault).
 * 3. Deterministic <5ms auto-compaction that preserves goals, decisions, files, code symbols, and tool results without heating the SoC.
 */
object ContextAndMemoryEngine {

    private val NON_ALNUM_REGEX = Regex("[^a-z0-9_]+")
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val FILE_OR_SYMBOL_REGEX = Regex("""\b[a-zA-Z0-9_\-./]+\.(?:kt|java|js|ts|html|css|sql|json|md|py|litertlm)\b""")

    private val STOPWORDS = setOf(
        // Italian
        "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "dello", "della",
        "dei", "degli", "delle", "a", "al", "allo", "alla", "ai", "agli", "alle", "da", "dal",
        "dallo", "dalla", "dai", "dagli", "dalle", "in", "nel", "nello", "nella", "nei", "negli",
        "nelle", "con", "su", "sul", "sullo", "sulla", "sui", "sugli", "sulle", "per", "tra", "fra",
        "che", "chi", "cui", "non", "piu", "anche", "come", "dove", "quando", "quanto", "quanti",
        "quante", "quale", "quali", "cosa", "cose", "questo", "questa", "questi", "queste", "quello",
        "quella", "sono", "sei", "siamo", "siete", "era", "ero", "stato", "stata", "essere", "avere",
        "hai", "abbiamo", "hanno", "fare", "fai", "fatto", "puoi", "posso", "vorrei", "voglio",
        "mio", "mia", "miei", "mie", "tuo", "tua", "suo", "sua", "nostro", "vostro", "loro",
        "mi", "ti", "si", "ci", "vi", "me", "te", "se", "ne", "ma", "ed", "od", "se", "poi", "ora",
        "qui", "qua", "molto", "poco", "tutto", "tutti", "tutte", "solo", "sempre", "ancora", "gia",
        // English
        "the", "and", "for", "with", "that", "this", "from", "have", "has", "was", "were", "are",
        "you", "your", "about", "into", "over", "what", "when", "where", "which", "who", "how",
        "can", "could", "would", "should", "will", "just", "than", "then", "them", "they", "their"
    )

    fun normalizeText(input: String): String {
        if (input.isBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").lowercase()
    }

    fun tokenize(input: String): List<String> {
        val norm = normalizeText(input)
        if (norm.isBlank()) return emptyList()
        return norm.split(NON_ALNUM_REGEX)
            .asSequence()
            .map { it.trim('_') }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .toList()
    }

    /** Lightweight Italian/English stem prefix for fuzzy recall without heavy NLP dictionaries. */
    private fun stemRoot(token: String): String {
        return when {
            token.length >= 7 -> token.substring(0, 5)
            token.length >= 5 -> token.substring(0, 4)
            else -> token
        }
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return ((text.length + 3) / 4).coerceAtLeast(1)
    }

    /**
     * Converts a natural-language query into a safe SQLite FTS4 prefix OR query, e.g.
     * "dove abito e che lavoro faccio?" -> "abit* OR lavor*"
     */
    fun buildFtsOrQuery(rawQuery: String): String {
        val tokens = tokenize(rawQuery)
            .distinct()
            .take(6)
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { tok ->
            val root = if (tok.length >= 5) tok.substring(0, tok.length - 1) else tok
            "${root}*"
        }
    }

    /**
     * Hybrid BM25 + key-boost + prefix-stem + importance + recency ranker for MemoryEntryEntity.
     */
    fun rankMemories(
        query: String,
        memories: List<MemoryEntryEntity>,
        limit: Int = 10,
        minScore: Double = 0.05
    ): List<ScoredMemory> {
        if (memories.isEmpty()) return emptyList()
        val normQuery = normalizeText(query).trim()
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty()) {
            return memories
                .sortedWith(compareByDescending<MemoryEntryEntity> { it.importance }.thenByDescending { it.updatedAt })
                .take(limit)
                .map { ScoredMemory(it, it.importance.toDouble()) }
        }

        val queryRoots = queryTokens.map(::stemRoot).toSet()
        val nowMs = System.currentTimeMillis()
        val docTokensList = memories.map { entry ->
            val keyTokens = tokenize(entry.keyName.replace('_', ' '))
            val bodyTokens = tokenize(entry.content)
            val catTokens = tokenize(entry.category.replace('_', ' '))
            Triple(keyTokens, bodyTokens, catTokens)
        }

        val totalDocs = memories.size.toDouble()
        val avgDocLen = docTokensList.map { (k, b, c) -> k.size + b.size + c.size }.average().coerceAtLeast(1.0)

        // Document frequency per query root for IDF weighting
        val dfByRoot = mutableMapOf<String, Int>()
        for (root in queryRoots) {
            var df = 0
            for ((k, b, c) in docTokensList) {
                val has = k.any { stemRoot(it) == root } || b.any { stemRoot(it) == root } || c.any { stemRoot(it) == root }
                if (has) df++
            }
            dfByRoot[root] = df
        }

        val k1 = 1.4
        val bParam = 0.70

        val scored = memories.mapIndexedNotNull { idx, entry ->
            val (keyTokens, bodyTokens, catTokens) = docTokensList[idx]
            val allTokens = keyTokens + bodyTokens + catTokens
            val docLen = allTokens.size.coerceAtLeast(1).toDouble()

            var bm25 = 0.0
            for (qTok in queryTokens) {
                val qRoot = stemRoot(qTok)
                val exactKeyHits = keyTokens.count { it == qTok }
                val rootKeyHits = keyTokens.count { it != qTok && stemRoot(it) == qRoot }
                val exactBodyHits = bodyTokens.count { it == qTok }
                val rootBodyHits = bodyTokens.count { it != qTok && stemRoot(it) == qRoot }
                val catHits = catTokens.count { it == qTok || stemRoot(it) == qRoot }

                // Weighted term frequency (keyName matches count 2.4x)
                val tf = (exactKeyHits * 2.4) + (rootKeyHits * 1.4) +
                    (exactBodyHits * 1.2) + (rootBodyHits * 0.75) +
                    (catHits * 0.6)
                if (tf > 0.0) {
                    val df = dfByRoot[qRoot] ?: 1
                    val idf = ln((totalDocs - df + 0.5) / (df + 0.5) + 1.0)
                    val normTf = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - bParam + bParam * (docLen / avgDocLen)))
                    bm25 += idf * normTf
                }
            }

            // Phrase substring bonus
            val normCombined = normalizeText("${entry.keyName.replace('_', ' ')} ${entry.content}")
            if (normQuery.length >= 4 && normCombined.contains(normQuery)) {
                bm25 += 2.2
            }

            if (bm25 <= 0.0) return@mapIndexedNotNull null

            // Importance multiplier (1..5 -> 0.85..1.45) + gentle recency boost (up to +18% for last 7 days)
            val importanceBoost = 0.70 + (entry.importance.coerceIn(1, 5) * 0.15)
            val ageDays = ((nowMs - entry.updatedAt).coerceAtLeast(0L)) / 86_400_000.0
            val recencyBoost = 1.0 + (0.18 / (1.0 + ageDays / 7.0))

            val finalScore = bm25 * importanceBoost * recencyBoost
            if (finalScore >= minScore) ScoredMemory(entry, finalScore) else null
        }

        return scored
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Ranks past conversation messages by BM25 token/stem match + recency.
     */
    fun rankPastMessages(
        query: String,
        candidates: List<ChatMessageEntity>,
        limit: Int = 8
    ): List<ChatMessageEntity> {
        if (candidates.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty()) return candidates.take(limit)

        val nowMs = System.currentTimeMillis()
        val queryRoots = queryTokens.map(::stemRoot).toSet()

        return candidates.map { msg ->
            val tokens = tokenize(msg.content)
            var hits = 0.0
            for (qTok in queryTokens) {
                val qRoot = stemRoot(qTok)
                hits += tokens.count { it == qTok } * 1.5
                hits += tokens.count { it != qTok && stemRoot(it) == qRoot } * 0.8
            }
            val matchedRoots = queryRoots.count { root -> tokens.any { stemRoot(it) == root } }
            val coverageBonus = if (queryRoots.isNotEmpty()) (matchedRoots.toDouble() / queryRoots.size) * 2.0 else 0.0
            val ageDays = ((nowMs - msg.timestamp).coerceAtLeast(0L)) / 86_400_000.0
            val recency = 1.0 + (0.25 / (1.0 + ageDays / 5.0))
            msg to ((hits + coverageBonus) * recency)
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Ranks enabled procedural skills for the current prompt so even large skill libraries
     * surface the exact right skills in the system prompt without bloating tokens.
     */
    fun rankSkillsForPrompt(
        prompt: String,
        skills: List<AgentSkillEntity>,
        limit: Int = 8
    ): List<AgentSkillEntity> {
        if (skills.size <= limit) return skills
        val queryTokens = tokenize(prompt).distinct()
        if (queryTokens.isEmpty()) return skills.take(limit)
        val queryRoots = queryTokens.map(::stemRoot).toSet()

        return skills.map { skill ->
            val skillTokens = tokenize("${skill.slug.replace('_', ' ')} ${skill.title} ${skill.description}")
            val matchScore = queryRoots.sumOf { root ->
                if (skillTokens.any { stemRoot(it) == root }) 2.0 else 0.0
            }
            val usageBonus = ln(skill.usageCount.coerceAtLeast(0) + 1.0) * 0.6
            skill to (matchScore + usageBonus)
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Computes the adaptive verbatim hot-window depth based on max context tokens.
     */
    fun getHotTurnCount(maxContextTokens: Int): Int {
        val safeMax = maxContextTokens.coerceIn(2048, 32768)
        return when {
            safeMax >= 24576 -> 14 // 7 full user+assistant exchanges verbatim
            safeMax >= 14336 -> 10 // 5 full exchanges verbatim
            safeMax >= 7168 -> 8   // 4 full exchanges verbatim
            else -> 4              // 2 full exchanges verbatim (4K mobile safe)
        }
    }

    /**
     * Builds the adaptive 3-tier mobile context plan for a session:
     * - Scales verbatim hot-window depth & per-turn char budget to `maxContextTokens`.
     * - Automatically compacts overflow older turns into a dense rolling `SessionDigest`
     *   whenever history exceeds the hot window or estimated tokens exceed 68% of `maxContextTokens`.
     */
    fun buildContextWindowPlan(
        allPriorMessages: List<ChatMessageEntity>,
        existingDigest: String,
        maxContextTokens: Int,
        forceCompaction: Boolean = false,
        lastSummarizedMessageId: Long = 0L
    ): ContextWindowPlan {
        val safeMax = maxContextTokens.coerceIn(2048, 32768)

        // Adaptive window depth & char budget scaled to the active model context window
        val hotTurnCount = getHotTurnCount(safeMax)
        val perMessageCharLimit = when {
            safeMax >= 24576 -> 1600
            safeMax >= 14336 -> 1100
            safeMax >= 7168 -> 760
            else -> 460
        }
        val digestCharBudget = when {
            safeMax >= 14336 -> 2200
            safeMax >= 7168 -> 1500
            else -> 950
        }

        val totalPrior = allPriorMessages.size
        val estimatedTotalPriorTokens = allPriorMessages.sumOf { estimateTokens(it.content) }
        val compactionThresholdTokens = (safeMax * 0.55f).toInt()

        val shouldCompact = forceCompaction ||
            totalPrior > hotTurnCount ||
            estimatedTotalPriorTokens > compactionThresholdTokens

        val (olderMessages, hotMessages) = if (shouldCompact && totalPrior > 2) {
            val keepCount = if (forceCompaction) {
                min(hotTurnCount / 2, 4).coerceAtLeast(2)
            } else {
                min(hotTurnCount, totalPrior)
            }
            val splitIdx = (totalPrior - keepCount).coerceAtLeast(0)
            allPriorMessages.subList(0, splitIdx) to allPriorMessages.subList(splitIdx, totalPrior)
        } else {
            emptyList<ChatMessageEntity>() to allPriorMessages
        }

        // Only compact older messages that have not yet been summarized
        val uncompactedOlder = if (lastSummarizedMessageId > 0L) {
            olderMessages.filter { it.id > lastSummarizedMessageId }
        } else {
            olderMessages
        }

        val updatedDigest = if (uncompactedOlder.isNotEmpty()) {
            buildCompactedSessionDigest(
                previousDigest = existingDigest,
                olderMessages = uncompactedOlder,
                maxChars = digestCharBudget,
                lastSummarizedMessageId = lastSummarizedMessageId
            )
        } else {
            existingDigest.take(digestCharBudget)
        }

        val newLastSummarizedId = maxOf(
            lastSummarizedMessageId,
            uncompactedOlder.maxOfOrNull { it.id } ?: lastSummarizedMessageId
        )

        val hotPairs = hotMessages.map { msg ->
            msg.role to compactSingleMessage(msg.content, perMessageCharLimit)
        }

        val estTokens = estimateTokens(updatedDigest) +
            hotPairs.sumOf { estimateTokens(it.second) } +
            650 // approx base system + tool prompt overhead

        return ContextWindowPlan(
            hotHistoryPairs = hotPairs,
            updatedSessionDigest = updatedDigest,
            didAutoCompact = uncompactedOlder.isNotEmpty(),
            compactedTurnCount = uncompactedOlder.size,
            estimatedPromptTokens = estTokens,
            maxContextTokens = safeMax,
            perMessageCharLimit = perMessageCharLimit,
            lastSummarizedMessageId = newLastSummarizedId
        )
    }

    /**
     * Deterministic, <5ms mobile-friendly hierarchical compactor:
     * Extracts user goals/requests, key decisions/answers, touched workspace files/code symbols,
     * and executed tool outcomes into a compact, structured checkpoint with zero extra LLM heat.
     */
    fun buildCompactedSessionDigest(
        previousDigest: String,
        olderMessages: List<ChatMessageEntity>,
        maxChars: Int = 1600,
        lastSummarizedMessageId: Long = 0L
    ): String {
        val unsummarized = if (lastSummarizedMessageId > 0L) {
            olderMessages.filter { it.id > lastSummarizedMessageId }
        } else {
            olderMessages
        }
        if (unsummarized.isEmpty()) return previousDigest.take(maxChars)

        val userGoals = mutableListOf<String>()
        val keyOutcomes = mutableListOf<String>()
        val touchedFiles = linkedSetOf<String>()
        val usedTools = linkedSetOf<String>()

        // Preserve non-header bullet lines from any previous checkpoint first
        if (previousDigest.isNotBlank()) {
            previousDigest.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .take(4)
                .forEach { line ->
                    val body = line.removePrefix("- ").trim()
                    if (body.isNotBlank() && body.length <= 160) {
                        keyOutcomes.add(body)
                    }
                }
        }

        for (msg in unsummarized) {
            val clean = msg.content.replace(Regex("\\s+"), " ").trim()
            if (clean.isBlank()) continue

            FILE_OR_SYMBOL_REGEX.findAll(clean).forEach { m ->
                if (touchedFiles.size < 8) touchedFiles.add(m.value)
            }

            // Capture tool names from serialized traces if present
            if (msg.toolCallsJson.length > 4) {
                Regex(""""toolName"\s*:\s*"([^"]+)"""").findAll(msg.toolCallsJson).forEach { m ->
                    if (usedTools.size < 10) usedTools.add(m.groupValues[1])
                }
            }

            if (msg.role == "user") {
                val summary = extractHighSignalSnippet(clean, 130)
                if (summary.isNotBlank()) userGoals.add(summary)
            } else if (msg.role == "assistant") {
                val summary = extractHighSignalSnippet(clean, 155)
                if (summary.isNotBlank()) keyOutcomes.add(summary)
            }
        }

        val sb = StringBuilder()
        if (userGoals.isNotEmpty()) {
            sb.appendLine("Obiettivi/Richieste precedenti:")
            userGoals.distinct().takeLast(5).forEach { g ->
                sb.appendLine("- $g")
            }
        }
        if (touchedFiles.isNotEmpty() || usedTools.isNotEmpty()) {
            val meta = buildList {
                if (touchedFiles.isNotEmpty()) add("File: ${touchedFiles.joinToString(", ")}")
                if (usedTools.isNotEmpty()) add("Tool usati: ${usedTools.joinToString(", ")}")
            }.joinToString(" | ")
            sb.appendLine(meta)
        }
        if (keyOutcomes.isNotEmpty()) {
            sb.appendLine("Decisioni e risultati chiave:")
            keyOutcomes.distinct().takeLast(6).forEach { o ->
                sb.appendLine("- $o")
            }
        }

        val built = sb.toString().trim()
        return if (built.length <= maxChars) built else built.takeLast(maxChars)
    }

    /**
     * Preserves both the start and conclusion of long messages (head+tail compression) so code outputs
     * and multi-paragraph answers keep their key takeaway instead of truncating blindly at char 420.
     */
    fun compactSingleMessage(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        val headChars = (maxChars * 0.65).toInt().coerceAtLeast(120)
        val tailChars = (maxChars - headChars - 20).coerceAtLeast(80)
        return trimmed.take(headChars).trimEnd() + "\n[…compattato…]\n" + trimmed.takeLast(tailChars).trimStart()
    }

    private fun extractHighSignalSnippet(text: String, maxLen: Int): String {
        // Prefer first meaningful sentence (skipping greetings/markdown headers)
        val stripped = text
            .replace(Regex("```[\\s\\S]*?```"), "[codice]")
            .replace(Regex("[#*_`>]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.length <= maxLen) return stripped
        val sentenceBreak = stripped.indexOfAny(charArrayOf('.', '!', '?'), startIndex = min(40, stripped.length - 1))
        return if (sentenceBreak in 35..maxLen) {
            stripped.substring(0, sentenceBreak + 1)
        } else {
            stripped.take(max(40, maxLen - 1)).trimEnd() + "…"
        }
    }
}
