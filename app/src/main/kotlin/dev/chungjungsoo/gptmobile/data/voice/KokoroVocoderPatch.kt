package dev.chungjungsoo.gptmobile.data.voice

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/** Streams an optimized Kokoro pack into the normal size/digest-checked installer. */
internal class KokoroVocoderPatch private constructor(
    private val base: RandomAccessFile,
    private val baseBytes: Long,
    private val instructions: DataInputStream,
    private var bytesLeft: Long
) : InputStream() {
    private var copyFromBase = false
    private var recordLeft = 0
    private var ended = false
    private val singleByte = ByteArray(1)

    override fun read(): Int = if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length < 0 || offset < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (ended) return -1
        if (recordLeft == 0) {
            when (instructions.readUnsignedByte()) {
                0 -> {
                    if (bytesLeft != 0L || instructions.read() != -1) throw IOException("Invalid end of Kokoro vocoder patch.")
                    ended = true
                    return -1
                }
                1 -> {
                    val position = instructions.readInt().toLong()
                    recordLeft = instructions.readInt()
                    if (position < 0 || position + recordLeft > baseBytes) throw IOException("Invalid source range in Kokoro vocoder patch.")
                    base.seek(position)
                    copyFromBase = true
                }
                2 -> {
                    recordLeft = instructions.readInt()
                    copyFromBase = false
                }
                else -> throw IOException("Unknown instruction in Kokoro vocoder patch.")
            }
            if (recordLeft <= 0 || recordLeft > bytesLeft) throw IOException("Invalid output range in Kokoro vocoder patch.")
        }
        val count = minOf(length, recordLeft)
        if (copyFromBase) base.readFully(buffer, offset, count) else instructions.readFully(buffer, offset, count)
        recordLeft -= count
        bytesLeft -= count
        return count
    }

    override fun close() {
        try {
            instructions.close()
        } finally {
            base.close()
        }
    }

    companion object {
        fun open(baseFile: File, compressed: InputStream, outputBytes: Long): KokoroVocoderPatch {
            val instructions = try {
                DataInputStream(GZIPInputStream(compressed, 64 * 1024).buffered())
            } catch (failure: Throwable) {
                compressed.close()
                throw failure
            }
            try {
                if (instructions.readInt() != 0x4b534d31) throw IOException("Unknown Kokoro vocoder patch format.")
                val baseBytes = baseFile.length()
                return KokoroVocoderPatch(RandomAccessFile(baseFile, "r"), baseBytes, instructions, outputBytes)
            } catch (failure: Throwable) {
                instructions.close()
                throw failure
            }
        }
    }
}
