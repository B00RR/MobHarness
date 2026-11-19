package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.tools.ToolCallTrace
import com.example.ui.theme.CodeCommentDark
import com.example.ui.theme.CodeKeywordDark
import com.example.ui.theme.CodeNumberDark
import com.example.ui.theme.CodeStringDark
import com.example.ui.theme.CodeSurfaceDark
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.delay

private sealed interface MessageSegment {
    data class Prose(val text: String) : MessageSegment
    data class CodeBlock(val language: String, val code: String) : MessageSegment
}

private val CODE_BLOCK_REGEX = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
private val INLINE_TOKEN_REGEX = Regex("(\\*\\*(.+?)\\*\\*|`([^`]+)`|\\*(?!\\s)(.+?)(?<!\\s)\\*)")
private val HEADING_REGEX = Regex("^(#{1,3})\\s+(.+)$")
private val BULLET_REGEX = Regex("^(\\s*)[-*]\\s+(.+)$")
private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while",
    "suspend", "import", "package", "data", "sealed", "const", "let", "function", "async", "await",
    "export", "default", "def", "from", "try", "catch", "finally", "SELECT", "FROM", "WHERE", "CREATE",
    "TABLE", "INSERT", "INTO", "VALUES", "ORDER", "BY", "UPDATE", "DELETE", "true", "false", "null"
)

private fun parseMessageSegments(markdown: String): List<MessageSegment> {
    val result = mutableListOf<MessageSegment>()
    var cursor = 0
    CODE_BLOCK_REGEX.findAll(markdown).forEach { match ->
        if (match.range.first > cursor) {
            val prose = markdown.substring(cursor, match.range.first).trim()
            if (prose.isNotEmpty()) result.add(MessageSegment.Prose(prose))
        }
        val lang = match.groupValues[1].ifBlank { "code" }
        val code = match.groupValues[2].trimEnd()
        result.add(MessageSegment.CodeBlock(lang, code))
        cursor = match.range.last + 1
    }
    if (cursor < markdown.length) {
        val tail = markdown.substring(cursor).trim()
        if (tail.isNotEmpty()) result.add(MessageSegment.Prose(tail))
    }
    if (result.isEmpty() && markdown.isNotBlank()) {
        result.add(MessageSegment.Prose(markdown))
    }
    return result
}

fun highlightCodeSyntax(code: String): AnnotatedString {
    val keywords = CODE_KEYWORDS
    return buildAnnotatedString {
        code.lines().forEachIndexed { lineIdx, line ->
            if (lineIdx > 0) append("\n")
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("--") || trimmed.startsWith("#")) {
                withStyle(SpanStyle(color = CodeCommentDark)) { append(line) }
            } else {
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    when {
                        c == '"' || c == '\'' || c == '`' -> {
                            val quote = c
                            val start = i++
                            while (i < line.length && line[i] != quote) {
                                if (line[i] == '\\' && i + 1 < line.length) i++
                                i++
                            }
                            if (i < line.length) i++
                            withStyle(SpanStyle(color = CodeStringDark)) {
                                append(line.substring(start, i))
                            }
                        }
                        c.isDigit() -> {
                            val start = i
                            while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
                            withStyle(SpanStyle(color = CodeNumberDark)) {
                                append(line.substring(start, i))
                            }
                        }
                        c.isLetter() || c == '_' -> {
                            val start = i
                            while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                            val word = line.substring(start, i)
                            if (word in keywords || word.uppercase() in keywords) {
                                withStyle(SpanStyle(color = CodeKeywordDark, fontWeight = FontWeight.SemiBold)) {
                                    append(word)
                                }
                            } else {
                                withStyle(SpanStyle(color = Color(0xFFE5ECE8))) { append(word) }
                            }
                        }
                        else -> {
                            withStyle(SpanStyle(color = Color(0xFFB5C4BD))) { append(c) }
                            i++
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RichMarkdownContent(
    text: String,
    textColor: Color,
    onSaveCodeToWorkspace: (language: String, code: String) -> Unit,
    onRunCodeInSandbox: (language: String, code: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(text) { parseMessageSegments(text) }
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val accentColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MessageSegment.Prose -> {
                    val formatted = remember(seg.text, accentColor, codeBg) {
                        formatInlineMarkdown(seg.text, accentColor, codeBg)
                    }
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
                is MessageSegment.CodeBlock -> {
                    val runnable = seg.language.lowercase() in setOf("js", "javascript", "sql", "sqlite", "json")
                    var copied by remember(seg.code) { mutableStateOf(false) }
                    LaunchedEffect(copied) {
                        if (copied) {
                            delay(1600)
                            copied = false
                        }
                    }

                    Surface(
                        color = CodeSurfaceDark,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF253438)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF141E22))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(CodeKeywordDark.copy(alpha = 0.85f))
                                    )
                                    Text(
                                        text = seg.language.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CodeKeywordDark
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(seg.code))
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            copied = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "Copia codice",
                                            tint = if (copied) StatusSuccess else Color(0xFFCDE2D8),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            if (copied) "Copiato" else "Copia",
                                            color = if (copied) StatusSuccess else Color(0xFFCDE2D8),
                                            fontSize = 12.sp
                                        )
                                    }
                                    TextButton(
                                        onClick = { onSaveCodeToWorkspace(seg.language, seg.code) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = "Salva nel workspace",
                                            tint = CodeKeywordDark,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Studio", color = CodeKeywordDark, fontSize = 12.sp)
                                    }
                                    if (runnable) {
                                        TextButton(
                                            onClick = { onRunCodeInSandbox(seg.language, seg.code) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Esegui in sandbox",
                                                tint = StatusSuccess,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text("Esegui", color = StatusSuccess, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = remember(seg.code) { highlightCodeSyntax(seg.code) },
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatInlineMarkdown(
    raw: String,
    accentColor: Color,
    inlineCodeBg: Color
): AnnotatedString = buildAnnotatedString {
    val lines = raw.lines()
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append("\n")
        val heading = HEADING_REGEX.matchEntire(line.trimEnd())
        if (heading != null) {
            val level = heading.groupValues[1].length
            val size = when (level) {
                1 -> 18.sp
                2 -> 16.sp
                else -> 15.sp
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size, color = accentColor)) {
                appendInlineSpans(heading.groupValues[2], accentColor, inlineCodeBg)
            }
            return@forEachIndexed
        }
        val bullet = BULLET_REGEX.matchEntire(line)
        val contentLine = if (bullet != null) {
            val indent = bullet.groupValues[1]
            withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) {
                append("$indent• ")
            }
            bullet.groupValues[2]
        } else {
            line
        }
        appendInlineSpans(contentLine, accentColor, inlineCodeBg)
    }
}

private fun AnnotatedString.Builder.appendInlineSpans(
    line: String,
    accentColor: Color,
    inlineCodeBg: Color
) {
    var pos = 0
    INLINE_TOKEN_REGEX.findAll(line).forEach { m ->
        if (m.range.first > pos) append(line.substring(pos, m.range.first))
        when {
            m.groupValues[2].isNotEmpty() -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(m.groupValues[2])
                }
            }
            m.groupValues[3].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        background = inlineCodeBg,
                        color = accentColor
                    )
                ) {
                    append(" ${m.groupValues[3]} ")
                }
            }
            m.groupValues[4].isNotEmpty() -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(m.groupValues[4])
                }
            }
            else -> append(m.value)
        }
        pos = m.range.last + 1
    }
    if (pos < line.length) append(line.substring(pos))
}

@Composable
fun ThinkingBlockCard(
    thinkingText: String,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = "Ragionamento",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ragionamento interno",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Espandi ragionamento",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.92f),
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
fun ToolCallTraceCard(
    trace: ToolCallTrace,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (trace.status) {
        "running" -> MaterialTheme.colorScheme.primary
        "success" -> StatusSuccess
        "denied" -> StatusWarning
        else -> StatusError
    }
    val statusLabel = when (trace.status) {
        "running" -> "In corso"
        "success" -> "OK"
        "denied" -> "Negato"
        else -> "Errore"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    when (trace.status) {
                        "running" -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        "success" -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Completato",
                            tint = StatusSuccess,
                            modifier = Modifier.size(18.dp)
                        )
                        "denied" -> Icon(
                            Icons.Outlined.Block,
                            contentDescription = "Rifiutato",
                            tint = StatusWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        else -> Icon(
                            Icons.Default.Error,
                            contentDescription = "Errore",
                            tint = StatusError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trace.toolName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (trace.argumentsSummary.isNotBlank()) {
                            Text(
                                text = trace.argumentsSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (trace.durationMs > 0) "${trace.durationMs}ms" else statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Dettagli tool",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && trace.resultSummary.isNotBlank(),
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Output strumento",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = trace.resultSummary,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
