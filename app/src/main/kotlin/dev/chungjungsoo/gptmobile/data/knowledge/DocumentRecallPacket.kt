package dev.chungjungsoo.gptmobile.data.knowledge

/** An immutable receipt for the exact document bytes admitted to a prompt. */
data class DocumentRecallSource(val document: KnowledgeDocument, val chunk: KnowledgeChunk)

data class DocumentRecallPacket(
    val chatId: Int,
    val scope: MemoryScope,
    val text: String = "",
    val sources: List<DocumentRecallSource> = emptyList()
)

data class DocumentRange(val text: String, val totalCharacters: Int, val nextOffset: Int)

internal fun reconstructDocumentRange(chunks: List<KnowledgeChunk>, offset: Int, end: Int): String = buildString {
    var cursor = offset
    chunks.sortedBy { it.startOffset }.forEach { chunk ->
        if (cursor >= end || chunk.endOffset <= cursor) return@forEach
        check(chunk.startOffset <= cursor) { "Document has a gap in its canonical source offsets." }
        val stop = minOf(end, chunk.endOffset)
        append(chunk.text.substring(cursor - chunk.startOffset, stop - chunk.startOffset))
        cursor = stop
    }
    check(cursor == end) { "Document source range is incomplete." }
}
