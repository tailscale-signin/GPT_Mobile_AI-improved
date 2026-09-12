package dev.chungjungsoo.gptmobile.util

/**
 * Incremental streaming text and markdown block diffing utility.
 *
 * Prevents full-AST re-tokenization and excessive Jetpack Compose recomposition jank
 * during high-speed token streaming (e.g. 100+ tokens/sec) by tracking stable completed
 * markdown blocks (code fences, paragraphs, lists) versus the actively streaming leaf tail.
 */
class StreamingDiffParser {

    data class Block(
        val id: Int,
        val type: BlockType,
        val content: String,
        val isClosed: Boolean
    )

    enum class BlockType {
        PARAGRAPH,
        CODE_BLOCK,
        HEADING,
        LIST_ITEM,
        BLOCKQUOTE
    }

    data class DiffResult(
        val stableBlocks: List<Block>,
        val activeTail: String,
        val hasChanges: Boolean
    )

    private var lastRawText: String = ""
    private var cachedBlocks: List<Block> = emptyList()
    private var blockIdCounter: Int = 0

    /**
     * Incrementally process an updated streaming text snapshot.
     * Extracts closed blocks and leaves the incomplete tail.
     */
    @Synchronized
    fun parseStream(rawText: String): DiffResult {
        if (rawText == lastRawText) {
            return DiffResult(cachedBlocks, "", hasChanges = false)
        }
        lastRawText = rawText

        val blocks = mutableListOf<Block>()
        val lines = rawText.lines()
        var currentBlockContent = StringBuilder()
        var inCodeBlock = false
        var codeBlockFence = ""

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // Code block fence detection
            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    // Flush existing non-code text
                    if (currentBlockContent.isNotEmpty()) {
                        blocks.add(
                            Block(
                                id = blockIdCounter++,
                                type = BlockType.PARAGRAPH,
                                content = currentBlockContent.toString().trimEnd(),
                                isClosed = true
                            )
                        )
                        currentBlockContent.clear()
                    }
                    inCodeBlock = true
                    codeBlockFence = trimmed.take(3)
                    currentBlockContent.append(line).append("\n")
                } else {
                    // Closing fence
                    currentBlockContent.append(line)
                    blocks.add(
                        Block(
                            id = blockIdCounter++,
                            type = BlockType.CODE_BLOCK,
                            content = currentBlockContent.toString(),
                            isClosed = true
                        )
                    )
                    currentBlockContent.clear()
                    inCodeBlock = false
                    codeBlockFence = ""
                }
                i++
                continue
            }

            if (inCodeBlock) {
                currentBlockContent.append(line).append("\n")
                i++
                continue
            }

            // Paragraph break
            if (line.isBlank() && currentBlockContent.isNotEmpty()) {
                blocks.add(
                    Block(
                        id = blockIdCounter++,
                        type = BlockType.PARAGRAPH,
                        content = currentBlockContent.toString().trimEnd(),
                        isClosed = true
                    )
                )
                currentBlockContent.clear()
            } else {
                currentBlockContent.append(line).append("\n")
            }
            i++
        }

        val activeTail = currentBlockContent.toString()
        cachedBlocks = blocks

        return DiffResult(
            stableBlocks = blocks,
            activeTail = activeTail,
            hasChanges = true
        )
    }

    /**
     * Reset parsing state when a new generation turn starts.
     */
    @Synchronized
    fun reset() {
        lastRawText = ""
        cachedBlocks = emptyList()
        blockIdCounter = 0
    }
}
