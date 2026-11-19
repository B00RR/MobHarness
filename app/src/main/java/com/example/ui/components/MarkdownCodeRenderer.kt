package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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

private sealed interface MessageSegment {
    data class Prose(val text: String) : MessageSegment
    data class CodeBlock(val language: String, val code: String) : MessageSegment
}

private fun parseMessageSegments(markdown: String): List<MessageSegment> {
    val result = mutableListOf<MessageSegment>()
    val regex = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
    var cursor = 0
    regex.findAll(markdown).forEach { match ->
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
    val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while",
        "suspend", "import", "package", "data", "sealed", "const", "let", "function", "async", "await",
        "export", "default", "def", "from", "try", "catch", "finally", "SELECT", "FROM", "WHERE", "CREATE",
        "TABLE", "INSERT", "INTO", "VALUES", "ORDER", "BY", "UPDATE", "DELETE", "true", "false", "null"
    )
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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MessageSegment.Prose -> {
                    Text(
                        text = formatInlineMarkdown(seg.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
                is MessageSegment.CodeBlock -> {
                    val runnable = seg.language.lowercase() in setOf("js", "javascript", "sql", "sqlite", "json")
                    Surface(
                        color = CodeSurfaceDark,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF151E21))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = seg.language.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CodeKeywordDark
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(seg.code)) }) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copia codice",
                                            tint = Color(0xFFCDE2D8),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copia", color = Color(0xFFCDE2D8), fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { onSaveCodeToWorkspace(seg.language, seg.code) }) {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = "Salva nel workspace",
                                            tint = CodeKeywordDark,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Studio", color = CodeKeywordDark, fontSize = 12.sp)
                                    }
                                    if (runnable) {
                                        TextButton(onClick = { onRunCodeInSandbox(seg.language, seg.code) }) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Esegui in sandbox",
                                                tint = StatusSuccess,
                                                modifier = Modifier.size(17.dp)
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
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = remember(seg.code) { highlightCodeSyntax(seg.code) },
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatInlineMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    val lines = raw.lines()
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append("\n")
        val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
        var pos = 0
        boldRegex.findAll(line).forEach { m ->
            if (m.range.first > pos) append(line.substring(pos, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            pos = m.range.last + 1
        }
        if (pos < line.length) append(line.substring(pos))
    }
}

@Composable
fun ThinkingBlockCard(
    thinkingText: String,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = "Ragionamento Gemma 4",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ragionamento interno (Thinking)",
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
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
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
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (trace.argumentsSummary.isNotBlank()) {
                            Text(
                                text = trace.argumentsSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (trace.durationMs > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${trace.durationMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Dettagli tool",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && trace.resultSummary.isNotBlank(),
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "Output strumento",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = trace.resultSummary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
