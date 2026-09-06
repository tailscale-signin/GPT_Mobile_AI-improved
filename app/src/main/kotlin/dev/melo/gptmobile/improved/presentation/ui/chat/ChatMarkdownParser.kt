package dev.melo.gptmobile.improved.presentation.ui.chat

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class LatexBlock(val formula: String) : MarkdownBlock()
    object Divider : MarkdownBlock()
    data class ListItem(val depth: Int, val ordered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    data class BlockQuote(val text: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
}

fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code blocks: ```
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeBuilder = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeBuilder.appendLine(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            blocks.add(MarkdownBlock.CodeBlock(language = lang, code = codeBuilder.toString().trimEnd()))
            continue
        }

        // Latex block: $$ ... $$
        if (line.trim().startsWith("$$")) {
            val formulaBuilder = StringBuilder()
            val rest = line.trim().removePrefix("$$")
            if (rest.endsWith("$$") && rest.length > 2) {
                blocks.add(MarkdownBlock.LatexBlock(rest.removeSuffix("$$").trim()))
                i++
                continue
            }
            formulaBuilder.appendLine(rest)
            i++
            while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                formulaBuilder.appendLine(lines[i])
                i++
            }
            if (i < lines.size) {
                formulaBuilder.append(lines[i].trim().removeSuffix("$$"))
                i++
            }
            blocks.add(MarkdownBlock.LatexBlock(formulaBuilder.toString().trim()))
            continue
        }

        // Headings
        val headingMatch = Regex("^(#{1,6})\\s+(.+)").find(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        // Horizontal Rule: --- or ***
        if (line.trim().matches(Regex("^([-*_])\\1{2,}$"))) {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        // Blockquote: >
        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.BlockQuote(quoteLines.joinToString("\n")))
            continue
        }

        // Unordered list: - or *
        val unorderedMatch = Regex("^(\\s*)[-*+]\\s+(.+)").find(line)
        if (unorderedMatch != null) {
            val indent = unorderedMatch.groupValues[1].length / 2
            val text = unorderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.ListItem(depth = indent, ordered = false, index = 0, text = text))
            i++
            continue
        }

        // Ordered list: 1.
        val orderedMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.+)").find(line)
        if (orderedMatch != null) {
            val indent = orderedMatch.groupValues[1].length / 2
            val index = orderedMatch.groupValues[2].toIntOrNull() ?: 1
            val text = orderedMatch.groupValues[3]
            blocks.add(MarkdownBlock.ListItem(depth = indent, ordered = true, index = index, text = text))
            i++
            continue
        }

        // Empty line
        if (line.isBlank()) {
            i++
            continue
        }

        // Default: Paragraph
        val paraBuilder = StringBuilder(line)
        i++
        while (i < lines.size &&
            lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith("```") &&
            !lines[i].trimStart().startsWith("#") &&
            !lines[i].trimStart().startsWith(">") &&
            !lines[i].trimStart().startsWith("- ") &&
            !lines[i].trimStart().startsWith("* ") &&
            !Regex("^\\s*\\d+\\.").containsMatchIn(lines[i])
        ) {
            paraBuilder.append("\n").append(lines[i])
            i++
        }
        blocks.add(MarkdownBlock.Paragraph(paraBuilder.toString()))
    }

    return blocks
}
