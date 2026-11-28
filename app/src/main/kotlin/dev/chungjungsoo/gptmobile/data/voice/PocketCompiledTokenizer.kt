package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The pinned English SentencePiece unigram model uses identity normalization, byte fallback,
 * a dummy prefix and escaped spaces. Adapted from john-rocky/LiteRT-Models (MIT), with a bounded
 * protobuf reader so the published original tokenizer can be verified and downloaded directly.
 */
internal class PocketCompiledTokenizer private constructor(table: List<Piece>) {
    internal data class Piece(val text: String, val score: Float, val type: Int)
    internal constructor(file: File) : this(readTable(file.readBytes()))

    private val pieces = HashMap<String, Pair<Int, Float>>()
    private val byteIds = IntArray(256) { -1 }
    private val idToPiece = table
    private var maxLength = 1
    private val byteScore: Float

    init {
        require(table.size == 4_000) { "POCKET_TOKENIZER_VOCABULARY" }
        var minimum = Float.POSITIVE_INFINITY
        table.forEachIndexed { id, piece ->
            require(piece.score.isFinite()) { "POCKET_TOKENIZER_SCORE" }
            when (piece.type) {
                1 -> {
                    val key = String(piece.text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
                    require(key.isNotEmpty()) { "POCKET_TOKENIZER_EMPTY_PIECE" }
                    pieces[key] = id to piece.score
                    maxLength = maxOf(maxLength, key.length)
                    minimum = minOf(minimum, piece.score)
                }
                6 -> {
                    require(piece.text.matches(Regex("<0x[0-9A-F]{2}>"))) { "POCKET_TOKENIZER_BYTE" }
                    byteIds[piece.text.substring(3, 5).toInt(16)] = id
                }
            }
        }
        require(minimum.isFinite() && byteIds.all { it >= 0 }) { "POCKET_TOKENIZER_BYTE_FALLBACK" }
        byteScore = minimum - 10f
    }

    fun encode(text: String): IntArray {
        require(text.length <= VoiceIncrementalStreamLimits.MAX_INPUT_BYTES) { "POCKET_TEXT_LIMIT" }
        val normalized = String(("▁" + text.replace(' ', '▁')).toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val best = FloatArray(normalized.length + 1) { Float.NEGATIVE_INFINITY }
        val backPosition = IntArray(best.size)
        val backId = IntArray(best.size)
        best[0] = 0f
        for (index in normalized.indices) {
            if (best[index] == Float.NEGATIVE_INFINITY) continue
            for (length in 1..minOf(maxLength, normalized.length - index)) {
                val hit = pieces[normalized.substring(index, index + length)] ?: continue
                val score = best[index] + hit.second
                if (score > best[index + length]) {
                    best[index + length] = score
                    backPosition[index + length] = index
                    backId[index + length] = hit.first
                }
            }
            val score = best[index] + byteScore
            if (score > best[index + 1]) {
                best[index + 1] = score
                backPosition[index + 1] = index
                backId[index + 1] = byteIds[normalized[index].code and 255]
            }
        }
        val reverse = ArrayList<Int>()
        var position = normalized.length
        while (position > 0) {
            reverse.add(backId[position])
            position = backPosition[position]
        }
        return reverse.asReversed().toIntArray()
    }

    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        ids.forEach { id ->
            val piece = idToPiece[id]
            if (piece.type == 6) bytes.write(piece.text.substring(3, 5).toInt(16)) else bytes.write(piece.text.toByteArray(Charsets.UTF_8))
        }
        return bytes.toString(Charsets.UTF_8.name()).replace('▁', ' ').removePrefix(" ")
    }

    companion object {
        internal fun readTable(bytes: ByteArray): List<Piece> {
            require(bytes.size in 1..1_000_000) { "POCKET_TOKENIZER_SIZE" }
            val input = Proto(bytes)
            val table = ArrayList<Piece>()
            while (input.remaining()) {
                val tag = input.varint()
                if (tag == 10) {
                    require(table.size < 4_000) { "POCKET_TOKENIZER_VOCABULARY" }
                    val piece = Proto(input.bytes())
                    var text: String? = null
                    var score = 0f
                    var type = 1
                    while (piece.remaining()) {
                        when (val field = piece.varint()) {
                            10 -> text = piece.bytes().toString(Charsets.UTF_8)
                            21 -> score = Float.fromBits(piece.fixed32())
                            24 -> type = piece.varint()
                            else -> piece.skip(field)
                        }
                    }
                    table += Piece(requireNotNull(text) { "POCKET_TOKENIZER_PIECE" }, score, type)
                } else {
                    input.skip(tag)
                }
            }
            return table
        }
    }

    private class Proto(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun remaining() = buffer.hasRemaining()
        fun fixed32(): Int = buffer.int
        fun varint(): Int {
            var result = 0
            for (shift in 0..28 step 7) {
                require(buffer.hasRemaining()) { "POCKET_TOKENIZER_TRUNCATED" }
                val value = buffer.get().toInt() and 255
                result = result or ((value and 127) shl shift)
                if (value and 128 == 0) return result
            }
            error("POCKET_TOKENIZER_VARINT")
        }
        fun bytes(): ByteArray {
            val size = varint()
            require(size in 0..buffer.remaining()) { "POCKET_TOKENIZER_LENGTH" }
            return ByteArray(size).also(buffer::get)
        }
        fun skip(tag: Int) {
            when (tag and 7) {
                0 -> varint()
                1 -> buffer.position(buffer.position() + 8)
                2 -> {
                    val size = varint()
                    require(size in 0..buffer.remaining()) { "POCKET_TOKENIZER_LENGTH" }
                    buffer.position(buffer.position() + size)
                }
                5 -> buffer.position(buffer.position() + 4)
                else -> error("POCKET_TOKENIZER_WIRE")
            }
        }
    }
}
