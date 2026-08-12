package com.example.aicode.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/** Small, dependency-free Markdown renderer for chat messages. */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    style: TextStyle,
) {
    val rendered = remember(text) { parseBasicMarkdown(text) }
    Text(text = rendered, color = color, style = style)
}

internal fun parseBasicMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val lines = source.lines()
    var fencedCode = false

    lines.forEachIndexed { index, originalLine ->
        val trimmed = originalLine.trimStart()
        if (trimmed.startsWith("```")) {
            fencedCode = !fencedCode
        } else if (fencedCode) {
            pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0xFFF1F5F9),
                    color = Color(0xFF0F3D52),
                ),
            )
            append(originalLine)
            pop()
        } else {
            val heading = Regex("^(#{1,3})\\s+(.*)$").matchEntire(trimmed)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    pushStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = when (level) {
                                1 -> 22.sp
                                2 -> 19.sp
                                else -> 17.sp
                            },
                        ),
                    )
                    appendInlineMarkdown(heading.groupValues[2])
                    pop()
                }
                Regex("^[-*+]\\s+.*$").matches(trimmed) -> {
                    append("• ")
                    appendInlineMarkdown(trimmed.replaceFirst(Regex("^[-*+]\\s+"), ""))
                }
                trimmed.startsWith("> ") -> {
                    pushStyle(SpanStyle(color = Color(0xFF64748B), fontStyle = FontStyle.Italic))
                    append("│ ")
                    appendInlineMarkdown(trimmed.removePrefix("> "))
                    pop()
                }
                else -> appendInlineMarkdown(originalLine)
            }
        }
        if (index != lines.lastIndex && !trimmed.startsWith("```")) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(value: String) {
    var index = 0
    while (index < value.length) {
        if (value[index] == '\\' && index + 1 < value.length) {
            append(value[index + 1])
            index += 2
            continue
        }

        val token = when {
            value.startsWith("**", index) -> "**"
            value.startsWith("__", index) -> "__"
            value.startsWith("~~", index) -> "~~"
            value[index] == '`' -> "`"
            value[index] == '*' -> "*"
            value[index] == '_' -> "_"
            else -> null
        }
        if (token == null) {
            append(value[index])
            index += 1
            continue
        }

        val closing = value.indexOf(token, startIndex = index + token.length)
        if (closing <= index + token.length) {
            append(token)
            index += token.length
            continue
        }

        val inner = value.substring(index + token.length, closing)
        val span = when (token) {
            "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
            "*", "_" -> SpanStyle(fontStyle = FontStyle.Italic)
            "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            else -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color(0xFFE8EEF3),
                color = Color(0xFF0F3D52),
            )
        }
        pushStyle(span)
        if (token == "`") append(inner) else appendInlineMarkdown(inner)
        pop()
        index = closing + token.length
    }
}
