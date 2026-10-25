package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.CombinedDocumentDao
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedPayloadBlockReference
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CombinedPayloadReference(val id: String, val count: Int, val length: Int, val digest: String, val storageVersion: Int = 1, val blocks: List<CombinedPayloadBlockReference> = emptyList()) {
    fun encode(): String = Json.encodeToString(this)
}

/** A page size is a storage/working-set boundary, never a limit on the document. */
internal object CombinedPayload {
    const val PAGE_CHARACTERS = 8192
    const val PAGE_BATCH = 8

    fun pages(text: String): Sequence<Pair<Int, String>> = sequence {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + PAGE_CHARACTERS)
            if (end < text.length) {
                val paragraph = text.lastIndexOf("\n\n", end - 2).takeIf { it >= start + PAGE_CHARACTERS / 2 }
                val line = text.lastIndexOf('\n', end - 1).takeIf { it >= start + PAGE_CHARACTERS / 2 }
                end = paragraph?.plus(2) ?: line?.plus(1) ?: end
                val preferred = end
                if (!characters.isBoundary(end)) end = characters.preceding(end)
                while (end > start && !safeUnicodeBoundary(text, end)) end = characters.preceding(end)
                // An adversarial grapheme can itself exceed a storage page. Retain
                // every code point in bounded rows; ordinary graphemes stay whole.
                if (end <= start) {
                    end = preferred
                    if (Character.isHighSurrogate(text[end - 1])) end--
                }
            }
            yield(start to text.substring(start, end))
            start = end
        }
    }

    private fun safeUnicodeBoundary(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return true
        val before = text.codePointBefore(index)
        val after = text.codePointAt(index)
        val type = Character.getType(after)
        if (before == 0x200D || after == 0x200D || after in 0x1F3FB..0x1F3FF || after in 0xFE00..0xFE0F || after in 0xE0100..0xE01EF || after in 0xE0020..0xE007F || type in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) || before == 13 && after == 10) return false
        if (before in 0x1F1E6..0x1F1FF && after in 0x1F1E6..0x1F1FF) {
            var cursor = index
            var regionalIndicators = 0
            while (cursor > 0 && text.codePointBefore(cursor) in 0x1F1E6..0x1F1FF) {
                cursor -= Character.charCount(text.codePointBefore(cursor))
                regionalIndicators++
            }
            if (regionalIndicators % 2 == 1) return false
        }
        return true
    }

    suspend fun write(dao: CombinedDocumentDao, runId: String, text: String): CombinedPayloadReference {
        val digest = researchHash(text)
        val id = "payload:$digest"
        val references = mutableListOf<CombinedPayloadBlockReference>()
        val batch = ArrayList<CombinedDurableBlock>(PAGE_BATCH)
        for ((_, page) in pages(text)) {
            val pageDigest = researchHash(page)
            val reference = CombinedPayloadBlockReference(pageDigest, page.length)
            references += reference
            batch += CombinedDurableBlock(runId, reference.payloadId, 0, 0, page, pageDigest, null, page.toByteArray(Charsets.UTF_8).size)
            if (batch.size == PAGE_BATCH) {
                writeBatch(dao, batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) writeBatch(dao, batch)
        return CombinedPayloadReference(id, references.size, text.length, digest, storageVersion = 2, blocks = references)
    }

    private suspend fun writeBatch(dao: CombinedDocumentDao, batch: List<CombinedDurableBlock>) {
        val unique = batch.distinctBy { it.payloadId }
        dao.insertCombinedDurableBlocks(unique)
        val first = batch.first()
        val stored = dao.combinedSharedBlocks(first.runId, unique.map { it.payloadId }).associateBy { it.payloadId }
        require(unique.all { stored[it.payloadId] == it }) { "COMBINED_BLOCK_REWRITE" }
    }

    fun reference(raw: String): CombinedPayloadReference = Json.decodeFromString<CombinedPayloadReference>(raw).also {
        require(it.count >= 0 && it.length >= 0 && it.id == "payload:${it.digest}" && (it.count == 0) == (it.length == 0)) { "COMBINED_PAYLOAD_REFERENCE" }
        require(it.storageVersion in 1..2) { "COMBINED_PAYLOAD_VERSION" }
        if (it.storageVersion == 1) {
            require(it.blocks.isEmpty()) { "COMBINED_PAYLOAD_INDEX_VERSION" }
        } else {
            require(it.blocks.size == it.count && it.blocks.sumOf { block -> block.length.toLong() } == it.length.toLong() && it.blocks.all { block -> block.length in 1..PAGE_CHARACTERS && block.digest.length == 64 && block.digest.all { character -> character in '0'..'9' || character in 'a'..'f' } }) { "COMBINED_PAYLOAD_INDEX" }
        }
    }

    fun storageIds(reference: CombinedPayloadReference): List<String> = if (reference.storageVersion == 1) listOf(reference.id) else reference.blocks.map { it.payloadId }.distinct()

    fun verifySharedBlock(runId: String, expected: CombinedPayloadBlockReference, row: CombinedDurableBlock) {
        require(row.runId == runId && row.payloadId == expected.payloadId && row.sequence == 0 && row.start == 0 && row.previousDigest == null) { "COMBINED_BLOCK_IDENTITY" }
        require(row.text.length == expected.length && row.text.length in 1..PAGE_CHARACTERS && row.digest == expected.digest && researchHash(row.text) == expected.digest && row.utf8Bytes == row.text.toByteArray(Charsets.UTF_8).size) { "COMBINED_BLOCK_INTEGRITY" }
    }

    suspend fun page(dao: CombinedDocumentDao, runId: String, reference: CombinedPayloadReference, first: Int, count: Int): List<CombinedDurableBlock> {
        require(first >= 0 && count in 1..PAGE_BATCH)
        if (first >= reference.count) return emptyList()
        val expectedCount = minOf(count, reference.count - first)
        if (reference.storageVersion == 2) {
            val selected = reference.blocks.subList(first, first + expectedCount)
            val stored = dao.combinedSharedBlocks(runId, selected.map { it.payloadId }.distinct()).associateBy { it.payloadId }
            var start = reference.blocks.take(first).sumOf { it.length }
            return selected.mapIndexed { offset, expected ->
                val row = requireNotNull(stored[expected.payloadId]) { "COMBINED_BLOCK_MISSING" }
                verifySharedBlock(runId, expected, row)
                val index = first + offset
                row.copy(payloadId = reference.id, sequence = index, start = start, previousDigest = reference.blocks.getOrNull(index - 1)?.digest).also { start += it.text.length }
            }.also { if (first + expectedCount == reference.count) require(start == reference.length) { "COMBINED_BLOCK_LENGTH" } }
        }
        val rows = dao.combinedDurableBlockPage(runId, reference.id, first, expectedCount)
        require(rows.size == expectedCount) { "COMBINED_BLOCK_MISSING" }
        val preceding = if (first > 0) dao.combinedDurableBlockPage(runId, reference.id, first - 1, 1).single() else null
        var previousDigest = preceding?.digest
        var expectedStart = preceding?.let { it.start + it.text.length } ?: 0
        rows.forEachIndexed { index, block ->
            require(block.runId == runId && block.payloadId == reference.id && block.sequence == first + index && block.start == expectedStart) { "COMBINED_BLOCK_IDENTITY" }
            require(block.text.isNotEmpty() && block.text.length <= PAGE_CHARACTERS && block.digest == researchHash(block.text) && block.previousDigest == previousDigest && block.utf8Bytes == block.text.toByteArray(Charsets.UTF_8).size) { "COMBINED_BLOCK_INTEGRITY" }
            previousDigest = block.digest
            expectedStart += block.text.length
        }
        if (first + rows.size == reference.count) require(expectedStart == reference.length) { "COMBINED_BLOCK_LENGTH" }
        return rows
    }

    suspend fun read(dao: CombinedDocumentDao, runId: String, raw: String): String {
        val reference = reference(raw)
        val result = StringBuilder(reference.length)
        var first = 0
        while (first < reference.count) {
            val page = page(dao, runId, reference, first, PAGE_BATCH)
            page.forEach { result.append(it.text) }
            first += page.size
        }
        return result.toString().also { require(it.length == reference.length && researchHash(it) == reference.digest) { "COMBINED_PAYLOAD_INTEGRITY" } }
    }
}
