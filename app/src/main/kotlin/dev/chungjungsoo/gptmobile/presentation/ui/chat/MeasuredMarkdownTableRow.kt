package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** A table's visual line count is its tallest cell, rather than the sum of columns. */
@Composable
internal fun MeasuredMarkdownTableRow(content: String, node: ASTNode, width: Dp, style: TextStyle, settings: AnnotatorSettings, header: Boolean = false, firstAppearanceAddsLines: Boolean = true) {
    val cells = remember(node) { node.children.filter { it.type == GFMTokenTypes.CELL } }
    val layouts = remember(node.startOffset, width, LocalDensity.current, style.fontSize, style.lineHeight, style.fontFamily, style.fontWeight, style.fontStyle, style.letterSpacing) { mutableMapOf<Int, TextLayoutResult>() }
    val source = content.substring(node.startOffset, node.endOffset)
    val reportLayout = rememberGitHubTableLayout("source:${node.startOffset}:0", node.startOffset, source, firstAppearanceAddsLines)
    val text = remember(content, node, settings, style, header) {
        cells.map { cell ->
            AnnotatedString.Builder().apply {
                pushStyle(style.toSpanStyle().copy(fontWeight = if (header) FontWeight.Bold else style.fontWeight))
                buildMarkdownAnnotatedString(content, cell, settings)
                pop()
            }.toAnnotatedString()
        }
    }
    val padding = LocalMarkdownDimens.current.tableCellPadding
    Row(Modifier.width(width).then(if (header) Modifier.semantics { heading() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, cell ->
            key(index) {
                Box(Modifier.weight(1f).padding(padding)) {
                    MarkdownText(
                        text[index],
                        cell,
                        Modifier,
                        style,
                        sourceContent = content,
                        onTextLayout = { result, _ ->
                            layouts[index] = result
                            if (cells.indices.all { layouts[it]?.layoutInput?.text?.text == text[it].text }) {
                                reportLayout(cells.indices.mapNotNull(layouts::get))
                            }
                        }
                    )
                }
            }
        }
    }
}
