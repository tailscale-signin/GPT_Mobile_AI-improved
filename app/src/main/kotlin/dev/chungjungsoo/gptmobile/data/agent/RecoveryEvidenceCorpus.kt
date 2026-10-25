package dev.chungjungsoo.gptmobile.data.agent

import android.util.AtomicFile
import dev.chungjungsoo.gptmobile.data.research.ResearchAtomicFiles
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.research.researchTextChunks
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class RecoveryEvidenceRecord(val id: String, val kind: String, val aliases: List<String>, val text: String)
internal data class RecoveryEvidencePage(val text: String, val nextOffset: Int, val totalCharacters: Int)

/** An immutable, deduplicated view of original records. A corpus never contains an older corpus. */
internal class RecoveryEvidenceCorpus private constructor(val records: List<RecoveryEvidenceRecord>) {
    val revision = researchHash(records.joinToString("\n") { "${it.id}|${it.aliases.sorted().joinToString("|")}" })
    val length = records.fold(0) { total, record -> Math.addExact(total, record.text.length) }
    val content: String by lazy { records.joinToString("") { it.text } }

    fun read(offset: Int, length: Int, evidenceId: String? = null): RecoveryEvidencePage {
        val selected = if (evidenceId == null) records else records.filter { it.id == evidenceId || evidenceId in it.aliases }.also { require(it.isNotEmpty()) { "Unknown saved evidence ID" } }
        val total = selected.fold(0) { count, record -> Math.addExact(count, record.text.length) }
        require(offset in 0..total && length > 0) { "Invalid recovery offset" }
        fun characterAt(position: Int): Char {
            var cursor = position
            selected.forEach { record ->
                if (cursor < record.text.length) return record.text[cursor]
                cursor -= record.text.length
            }
            error("Invalid recovery character position")
        }
        require(offset == total || offset == 0 || !characterAt(offset).isLowSurrogate() || !characterAt(offset - 1).isHighSurrogate()) { "Offset splits a Unicode character" }
        var end = (offset.toLong() + length).coerceAtMost(total.toLong()).toInt()
        if (end in 1 until total && characterAt(end - 1).isHighSurrogate() && characterAt(end).isLowSurrogate()) end += if (end - 1 == offset) 1 else -1
        val text = buildString(end - offset) {
            var cursor = 0
            for (record in selected) {
                val recordEnd = cursor + record.text.length
                if (recordEnd > offset && cursor < end) append(record.text, (offset - cursor).coerceAtLeast(0), (end - cursor).coerceAtMost(record.text.length))
                cursor = recordEnd
                if (cursor >= end) break
            }
        }
        return RecoveryEvidencePage(text, end, total)
    }

    class Builder {
        private val records = linkedMapOf<String, RecoveryEvidenceRecord>()
        fun add(kind: String, alias: String, text: String) {
            if (text.isBlank()) return
            val body = text.trimEnd() + "\n\n"
            val id = researchHash("$kind\n$body")
            val old = records[id]
            records[id] = old?.copy(aliases = (old.aliases + alias).distinct().sorted()) ?: RecoveryEvidenceRecord(id, kind, listOf(alias), body)
        }
        fun build(): RecoveryEvidenceCorpus = RecoveryEvidenceCorpus(records.values.toList())
    }
}

@Serializable
private data class RecoveryStoredRecord(val id: String, val kind: String, val aliases: List<String>, val chunks: List<String>)

@Serializable
private data class RecoveryCorpusManifest(val schemaVersion: Int = 1, val chatId: Int, val rootWorkId: String, val authority: String, val revision: String, val records: List<RecoveryStoredRecord>)

/** Content-addressed pieces are shared between retries in one chat; no recursive bundles are saved. */
internal class RecoveryEvidenceCorpusStore(private val directory: File) {
    fun retain(chatId: Int, rootWorkId: String, authority: String, corpus: RecoveryEvidenceCorpus): String {
        require(chatId > 0 && rootWorkId.isNotBlank() && authority.isNotBlank())
        check(directory.mkdirs() || directory.isDirectory)
        val stored = corpus.records.map { record ->
            val chunks = researchTextChunks(record.text).map { text ->
                val name = "$chatId-${researchHash(text)}.recovery-piece"
                immutable(File(directory, name), text.toByteArray())
                name
            }
            RecoveryStoredRecord(record.id, record.kind, record.aliases, chunks)
        }
        val manifest = RecoveryCorpusManifest(chatId = chatId, rootWorkId = rootWorkId, authority = authority, revision = corpus.revision, records = stored)
        val text = Json.encodeToString(manifest)
        val reference = "$chatId-${researchHash(text)}.recovery-corpus"
        immutable(File(directory, reference), text.toByteArray())
        return reference
    }

    fun read(reference: String, chatId: Int, rootWorkId: String, authority: String): RecoveryEvidenceCorpus {
        require(reference.matches(Regex("$chatId-[a-f0-9]{64}\\.recovery-corpus")))
        val text = atomicText(File(directory, reference))
        require(reference == "$chatId-${researchHash(text)}.recovery-corpus") { "Saved evidence manifest integrity check failed" }
        val manifest = Json.decodeFromString<RecoveryCorpusManifest>(text)
        require(manifest.schemaVersion == 1 && manifest.chatId == chatId && manifest.rootWorkId == rootWorkId && manifest.authority == authority) { "Saved evidence belongs to another recovery scope" }
        val builder = RecoveryEvidenceCorpus.Builder()
        manifest.records.forEach { record ->
            val body = record.chunks.joinToString("") { name ->
                require(name.matches(Regex("$chatId-[a-f0-9]{64}\\.recovery-piece")))
                val piece = atomicText(File(directory, name))
                require(name == "$chatId-${researchHash(piece)}.recovery-piece") { "Saved evidence body integrity check failed" }
                piece
            }
            require(record.id == researchHash("${record.kind}\n$body")) { "Saved evidence record integrity check failed" }
            record.aliases.forEach { builder.add(record.kind, it, body) }
        }
        return builder.build().also { require(it.revision == manifest.revision) { "Saved evidence revision changed" } }
    }

    fun deleteChat(chatId: Int) {
        directory.listFiles().orEmpty().filter { it.name.startsWith("$chatId-") }.forEach { ResearchAtomicFiles.locked(it) { AtomicFile(it).delete() } }
    }

    private fun immutable(file: File, bytes: ByteArray) = ResearchAtomicFiles.locked(file) {
        val atomic = AtomicFile(file)
        if (file.exists()) {
            require(atomic.openRead().use { it.readBytes().contentEquals(bytes) }) { "Saved evidence was modified" }
        } else {
            check(directory.usableSpace >= bytes.size + 8L * 1024 * 1024) { "Paused for device resources: insufficient recovery storage" }
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (failure: Throwable) {
                atomic.failWrite(stream)
                throw failure
            }
        }
    }

    private fun atomicText(file: File): String = ResearchAtomicFiles.locked(file) { AtomicFile(file).openRead().use { it.readBytes().decodeToString(throwOnInvalidSequence = true) } }
}
