package com.example.data.workspace

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

const val MAX_EDITABLE_FILE_BYTES: Int = 256 * 1024
const val MAX_DIFF_OUTPUT_LINES: Int = 40
const val MAX_DIFF_OUTPUT_CHARS: Int = 1100
const val MAX_DIFF_LINE_CHARS: Int = 160
internal const val TEMP_FILE_PREFIX: String = ".mobharn_write_"

class ConcurrentWorkspaceEditException(message: String) : IllegalStateException(message)

sealed interface WorkspaceWritePolicy {
    /** Agent tool write_workspace_file: allowed for new files or existing files read in full and unchanged. */
    data object AgentGuarded : WorkspaceWritePolicy

    /** Studio new-file creation: only allowed when the target file does not exist yet. */
    data object RequireAbsent : WorkspaceWritePolicy

    /** Studio editor save: must match the exact loaded base (or absence) and never overwrite partial/unreadable files. */
    data class MatchExpectedBase(
        val expectedBaseSha256: String?,
        val loadedInFull: Boolean = true
    ) : WorkspaceWritePolicy

    /** Explicit user save action (e.g., saving a code block from Chat to Studio). */
    data object ExplicitUserSave : WorkspaceWritePolicy
}

data class WorkspaceReadExcerpt(
    val normalizedPath: String,
    val formattedText: String,
    val isPartial: Boolean,
    val sha256: String?,
    val sizeBytes: Long
)

data class WorkspaceEditorDocument(
    val normalizedPath: String,
    val content: String,
    val existed: Boolean,
    val sha256: String?,
    val editable: Boolean,
    val notice: String? = null
)

data class WorkspaceMutationResult(
    val projectName: String,
    val normalizedPath: String,
    val snapshotId: Long?,
    val changed: Boolean,
    val existedBefore: Boolean,
    val existsAfter: Boolean,
    val newSha256: String?,
    val lineCount: Int,
    val diff: String
)

internal fun encodeStrictUtf8(text: String, maxBytes: Int = MAX_EDITABLE_FILE_BYTES): ByteArray {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val encoded = try {
        encoder.encode(CharBuffer.wrap(text))
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("Contenuto UTF-8 non valido: impossibile salvare.", e)
    }
    val size = encoded.remaining()
    if (size > maxBytes) {
        throw IllegalArgumentException("Contenuto troppo grande ($size byte; limite $maxBytes byte).")
    }
    return ByteArray(size).also { encoded.get(it) }
}

internal fun decodeStrictUtf8(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("Il file non contiene testo UTF-8 valido.", e)
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun countTextLines(text: String): Int =
    if (text.isEmpty()) 0 else splitDiffLines(text).size

private fun splitDiffLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var i = 0
    var start = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '\n' || ch == '\r') {
            lines.add(text.substring(start, i))
            if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                i++
            }
            start = i + 1
        }
        i++
    }
    if (start < text.length) {
        lines.add(text.substring(start))
    }
    return lines
}

private enum class DiffOpKind { CONTEXT, REMOVE, ADD }

private data class DiffLine(
    val kind: DiffOpKind,
    val oldLineNo: Int,
    val newLineNo: Int,
    val text: String
)

private fun clipDiffLine(line: String): String =
    if (line.length <= MAX_DIFF_LINE_CHARS) line else line.take(MAX_DIFF_LINE_CHARS - 1) + "…"

/**
 * Produces a bounded, real unified-style diff between two file states without allocating
 * unbounded DP tables or overflowing mobile context/UI budgets.
 */
fun buildBoundedUnifiedDiff(
    relativePath: String,
    beforeExists: Boolean,
    beforeBytes: ByteArray?,
    afterExists: Boolean,
    afterBytes: ByteArray?,
    maxLines: Int = MAX_DIFF_OUTPUT_LINES,
    maxChars: Int = MAX_DIFF_OUTPUT_CHARS
): String {
    val oldBytes = if (beforeExists) beforeBytes ?: ByteArray(0) else ByteArray(0)
    val newBytes = if (afterExists) afterBytes ?: ByteArray(0) else ByteArray(0)
    if (beforeExists == afterExists && oldBytes.contentEquals(newBytes)) {
        return "Nessuna modifica (file invariato)."
    }

    val oldHeader = if (beforeExists) "--- a/$relativePath" else "--- /dev/null"
    val newHeader = if (afterExists) "+++ b/$relativePath" else "+++ /dev/null"

    val oldText = if (beforeExists) runCatching { decodeStrictUtf8(oldBytes) }.getOrElse { String(oldBytes, Charsets.UTF_8) } else ""
    val newText = if (afterExists) runCatching { decodeStrictUtf8(newBytes) }.getOrElse { String(newBytes, Charsets.UTF_8) } else ""
    val oldLines = if (beforeExists) splitDiffLines(oldText) else emptyList()
    val newLines = if (afterExists) splitDiffLines(newText) else emptyList()

    if (beforeExists && afterExists && oldLines == newLines) {
        return "$oldHeader\n$newHeader\n@@ -1 +1 @@\n(Differenza di fine riga o byte finali: ${oldBytes.size} B -> ${newBytes.size} B)"
    }

    val diffLines = computeLineEdits(oldLines, newLines)
    val totalAdded = diffLines.count { it.kind == DiffOpKind.ADD }
    val totalRemoved = diffLines.count { it.kind == DiffOpKind.REMOVE }

    val formattedLines = buildList {
        add(oldHeader)
        add(newHeader)
        val contextRadius = 2
        val changeIndices = diffLines.indices.filter { diffLines[it].kind != DiffOpKind.CONTEXT }
        if (changeIndices.isEmpty()) {
            add("@@ -0,0 +0,0 @@")
            return@buildList
        }
        val hunks = mutableListOf<IntRange>()
        var hunkStart = (changeIndices.first() - contextRadius).coerceAtLeast(0)
        var hunkEnd = (changeIndices.first() + contextRadius).coerceAtMost(diffLines.lastIndex)
        for (idx in changeIndices.drop(1)) {
            val nextStart = (idx - contextRadius).coerceAtLeast(0)
            val nextEnd = (idx + contextRadius).coerceAtMost(diffLines.lastIndex)
            if (nextStart <= hunkEnd + 1) {
                hunkEnd = maxOf(hunkEnd, nextEnd)
            } else {
                hunks.add(hunkStart..hunkEnd)
                hunkStart = nextStart
                hunkEnd = nextEnd
            }
        }
        hunks.add(hunkStart..hunkEnd)

        for (hunk in hunks) {
            val slice = diffLines.subList(hunk.first, hunk.last + 1)
            val oldCount = slice.count { it.kind != DiffOpKind.ADD }
            val newCount = slice.count { it.kind != DiffOpKind.REMOVE }
            val firstOld = slice.firstOrNull { it.kind != DiffOpKind.ADD }?.oldLineNo
                ?: (slice.first().oldLineNo)
            val firstNew = slice.firstOrNull { it.kind != DiffOpKind.REMOVE }?.newLineNo
                ?: (slice.first().newLineNo)
            val oldStart = if (oldCount == 0) firstOld else firstOld
            val newStart = if (newCount == 0) firstNew else firstNew
            add("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            for (entry in slice) {
                val prefix = when (entry.kind) {
                    DiffOpKind.CONTEXT -> " "
                    DiffOpKind.REMOVE -> "-"
                    DiffOpKind.ADD -> "+"
                }
                add(prefix + clipDiffLine(entry.text))
            }
        }
    }

    val footer = "... (diff troncato: +$totalAdded/-$totalRemoved righe totali)"
    val sb = StringBuilder()
    var emittedLines = 0
    var truncated = false

    for (line in formattedLines) {
        val nextLen = sb.length + (if (sb.isEmpty()) 0 else 1) + line.length
        val reserve = footer.length + 1
        if (emittedLines >= maxLines || (nextLen + reserve > maxChars && emittedLines >= 3)) {
            truncated = true
            break
        }
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
        emittedLines++
    }
    if (truncated) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(footer)
    }
    return sb.toString()
}

private fun computeLineEdits(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
    var prefix = 0
    val minSize = minOf(oldLines.size, newLines.size)
    while (prefix < minSize && oldLines[prefix] == newLines[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < (oldLines.size - prefix) &&
        suffix < (newLines.size - prefix) &&
        oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
    ) {
        suffix++
    }

    val result = ArrayList<DiffLine>(oldLines.size + newLines.size)
    for (i in 0 until prefix) {
        result.add(DiffLine(DiffOpKind.CONTEXT, i + 1, i + 1, oldLines[i]))
    }

    val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
    val newMid = newLines.subList(prefix, newLines.size - suffix)
    val dpCells = oldMid.size.toLong() * newMid.size.toLong()

    if (oldMid.isNotEmpty() && newMid.isNotEmpty() && dpCells <= 200_000L) {
        val n = oldMid.size
        val m = newMid.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            val row = dp[i]
            val nextRow = dp[i + 1]
            for (j in m - 1 downTo 0) {
                row[j] = if (oldMid[i] == newMid[j]) {
                    nextRow[j + 1] + 1
                } else {
                    maxOf(nextRow[j], row[j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && oldMid[i] == newMid[j] -> {
                    result.add(DiffLine(DiffOpKind.CONTEXT, prefix + i + 1, prefix + j + 1, oldMid[i]))
                    i++
                    j++
                }
                i < n && (j == m || dp[i + 1][j] >= dp[i][j + 1]) -> {
                    result.add(DiffLine(DiffOpKind.REMOVE, prefix + i + 1, prefix + j, oldMid[i]))
                    i++
                }
                else -> {
                    result.add(DiffLine(DiffOpKind.ADD, prefix + i, prefix + j + 1, newMid[j]))
                    j++
                }
            }
        }
    } else {
        // Bounded fallback for massive rewrites: avoids quadratic memory while preserving real removed/added lines.
        oldMid.forEachIndexed { idx, line ->
            result.add(DiffLine(DiffOpKind.REMOVE, prefix + idx + 1, prefix, line))
        }
        newMid.forEachIndexed { idx, line ->
            result.add(DiffLine(DiffOpKind.ADD, prefix + oldMid.size, prefix + idx + 1, line))
        }
    }

    val oldSuffixStart = oldLines.size - suffix
    val newSuffixStart = newLines.size - suffix
    for (k in 0 until suffix) {
        result.add(
            DiffLine(
                DiffOpKind.CONTEXT,
                oldSuffixStart + k + 1,
                newSuffixStart + k + 1,
                oldLines[oldSuffixStart + k]
            )
        )
    }
    return result
}
