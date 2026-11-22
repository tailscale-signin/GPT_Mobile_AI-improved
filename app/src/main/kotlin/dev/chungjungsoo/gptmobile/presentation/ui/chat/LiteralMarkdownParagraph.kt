package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/** Only literal single-line prose can bypass rebuilding Markdown annotations. */
internal fun literalMarkdownParagraph(source: String, node: ASTNode): String? {
    if (node.startOffset < 0 || node.endOffset > source.length || node.startOffset >= node.endOffset) return null
    val text = source.substring(node.startOffset, node.endOffset)
    if (text.any { it in "\\*_`~[]<>&$\r\n\uE000\uE001" }) return null
    if (node.children.any { child ->
            child.children.isNotEmpty() ||
                child.type != MarkdownTokenTypes.TEXT &&
                streamVisibleMarkdownTokenText(child.type, source.substring(child.startOffset, child.endOffset)) == null &&
                source.substring(child.startOffset, child.endOffset).any { !it.isWhitespace() }
        }
    ) {
        return null
    }
    return text
}
