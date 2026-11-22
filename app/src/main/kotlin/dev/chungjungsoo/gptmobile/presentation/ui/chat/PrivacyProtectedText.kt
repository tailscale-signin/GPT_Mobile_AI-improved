package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles

/** Receipt-validated values for this displayed message/revision only; unrelated messages stay empty. */
internal val LocalPrivacyProtectedValues = staticCompositionLocalOf<List<String>> { emptyList() }
internal const val PRIVACY_VERBATIM_SOURCE = "privacy-verbatim-source"

internal fun privacyProtectedForeground(surface: Color): Color =
    ResponseSourceAppearance.gatewayForeground(Color(0xFFFFD740), surface)

@Composable
internal fun rememberPrivacyProtectedText(
    text: AnnotatedString,
    surface: Color = LocalAnswerSurface.current,
    values: List<String> = LocalPrivacyProtectedValues.current,
    sourceText: String? = null
): AnnotatedString {
    if (values.isEmpty()) return text
    val foreground = remember(surface) { privacyProtectedForeground(surface) }
    return remember(text, values, foreground, sourceText) { privacyProtectedText(text, values, foreground, sourceText) }
}

/** Preserve text, selection, paragraph styling and link targets while giving privacy colour priority. */
internal fun privacyProtectedText(text: AnnotatedString, values: List<String>, foreground: Color, sourceText: String? = null): AnnotatedString {
    val protectedRanges = if (sourceText == null || sourceText == text.text) {
        privacyProtectedRanges(text.text, values)
    } else {
        // A Markdown transform can assemble a new literal (A**li**ce) or hide
        // one in a URL destination. Only unchanged, actually appended source
        // runs may supply evidence; matching the rendered string alone cannot.
        val runs = text.getStringAnnotations(PRIVACY_VERBATIM_SOURCE, 0, text.length).mapNotNull { run ->
            val sourceStart = run.item.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val sourceEnd = run.item.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
            PrivacyVerbatimRun(sourceStart, sourceEnd, run.start, run.end)
        }
        privacyProtectedRangesFromSource(text.text, values, sourceText, runs)
    }
    if (protectedRanges.isEmpty()) return text
    val links = text.flatMapAnnotations { annotation ->
        val link = annotation.item as? LinkAnnotation
        if (link == null || protectedRanges.none { it.first < annotation.end && it.last >= annotation.start }) {
            listOf(annotation)
        } else {
            // Compose applies link interaction styles after ordinary span styles.
            // Split only intersecting links so the protected words remain yellow
            // without recolouring the rest of a link or losing its tap listener.
            val boundaries = buildSet {
                add(annotation.start)
                add(annotation.end)
                protectedRanges.forEach { range ->
                    if (range.first < annotation.end && range.last >= annotation.start) {
                        add(maxOf(annotation.start, range.first))
                        add(minOf(annotation.end, range.last + 1))
                    }
                }
            }.sorted()
            boundaries.zipWithNext { start, end ->
                val protected = protectedRanges.any { it.first <= start && it.last >= end - 1 }
                AnnotatedString.Range(
                    item = if (protected) link.withPrivacyColor(foreground) else link,
                    start = start,
                    end = end,
                    tag = annotation.tag
                )
            }
        }
    }
    return AnnotatedString.Builder(links).apply {
        protectedRanges.forEach { addStyle(SpanStyle(color = foreground), it.first, it.last + 1) }
    }.toAnnotatedString()
}

private fun LinkAnnotation.withPrivacyColor(foreground: Color): LinkAnnotation {
    val original = styles
    val protected = TextLinkStyles(
        style = (original?.style ?: SpanStyle()).copy(color = foreground),
        focusedStyle = original?.focusedStyle?.copy(color = foreground),
        hoveredStyle = original?.hoveredStyle?.copy(color = foreground),
        pressedStyle = original?.pressedStyle?.copy(color = foreground)
    )
    return when (this) {
        is LinkAnnotation.Url -> copy(styles = protected)
        is LinkAnnotation.Clickable -> copy(styles = protected)
        else -> this
    }
}
