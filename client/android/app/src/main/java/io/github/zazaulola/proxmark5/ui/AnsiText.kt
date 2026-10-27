package io.github.zazaulola.proxmark5.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import io.github.zazaulola.proxmark5.parse.Ansi

/** One line of client output, rendering its ANSI colors. */
@Composable
fun AnsiText(line: String, modifier: Modifier = Modifier, fontSize: TextUnit = TextUnit.Unspecified) {
    val base = LocalContentColor.current
    val annotated: AnnotatedString = remember(line, base) {
        val styled = Ansi.parse(line)
        buildAnnotatedString {
            var pos = 0
            for (span in styled.spans) {
                if (span.start > pos) append(styled.text.substring(pos, span.start))
                withStyle(
                    SpanStyle(
                        color = span.fg?.toColor() ?: base,
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        textDecoration = if (span.underline) TextDecoration.Underline else null,
                    )
                ) {
                    append(styled.text.substring(span.start, span.end.coerceAtMost(styled.text.length)))
                }
                pos = span.end
            }
            if (pos < styled.text.length) append(styled.text.substring(pos))
        }
    }
    Text(annotated, modifier = modifier, fontSize = fontSize)
}
