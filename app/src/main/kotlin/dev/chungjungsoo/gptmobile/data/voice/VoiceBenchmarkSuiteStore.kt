package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** Each stage owns a separately committed export. One campaign can contain hundreds of measurements. */
internal class VoiceBenchmarkSuiteStore(private val root: File) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun begin(manifest: VoiceBenchManifest, performance: VoicePerformanceSnapshot, feedback: VoiceSynthesisFeedback?, resumeFrom: VoiceBenchManifest? = null) = synchronized(lock) {
        val folder = directory(manifest.runId)
        val previous = preferredDirectory() ?: runs().firstOrNull()
        require(manifest.resumedFromRunId == resumeFrom?.runId)
        require(resumeFrom != null || manifest.reusedStageIndices.isEmpty())
        if (resumeFrom != null) {
            require(previous == directory(resumeFrom.runId))
            val saved = read(resumeFrom.runId)
            require(saved == resumeFrom || saved.state == "RUNNING" && saved.stopped("INTERRUPTED") == resumeFrom)
            require(manifest.reusedStageIndices.distinct() == manifest.reusedStageIndices)
            require(
                manifest.reusedStageIndices.all { index ->
                    index in resumeFrom.results.indices &&
                        index in manifest.results.indices &&
                        manifest.results[index] == resumeFrom.results[index] &&
                        resumeFrom.results[index].status in setOf(VoiceBenchStatus.MEASURED, VoiceBenchStatus.PARTIAL, VoiceBenchStatus.FAILED)
                }
            )
        }
        check(folder.mkdirs())
        try {
            atomic(File(folder, "performance.json")) { writeVoicePerformanceExport(performance, feedback, it) }
            if (resumeFrom != null) {
                val source = directory(resumeFrom.runId)
                for (index in manifest.reusedStageIndices) {
                    val evidence = File(source, "stage-$index.json")
                    if (evidence.isFile) {
                        atomic(File(folder, evidence.name)) { out -> evidence.inputStream().buffered().use { it.copyTo(out) } }
                    } else {
                        check(resumeFrom.results[index].measurements == 0) { "Missing saved benchmark evidence" }
                    }
                }
            }
            save(manifest)
            atomic(File(root, "latest")) { it.write(manifest.runId.toByteArray()) }
        } catch (error: Throwable) {
            folder.deleteRecursively()
            throw error
        }
        runs().filterNot { it == folder || it == previous }.forEach { it.deleteRecursively() }
    }

    /** The immediately preceding interrupted campaign remains independently exportable. */
    fun previous(manifest: VoiceBenchManifest): VoiceBenchManifest? = synchronized(lock) {
        manifest.resumedFromRunId?.let { id ->
            runCatching { read(id) }.getOrNull()?.let { if (it.state == "RUNNING") it.stopped("INTERRUPTED") else it }
        }
    }

    fun save(manifest: VoiceBenchManifest) = synchronized(lock) {
        atomic(File(directory(manifest.runId), "manifest.json")) { it.write(json.encodeToString(manifest).toByteArray()) }
    }

    fun evidence(id: String, index: Int, write: (OutputStream) -> Unit) = synchronized(lock) {
        require(index >= 0)
        atomic(File(directory(id), "stage-$index.json"), write)
    }

    fun latest(active: Boolean = false): VoiceBenchManifest? = synchronized(lock) {
        (listOfNotNull(preferredDirectory()) + runs()).distinct().firstNotNullOfOrNull { folder ->
            runCatching { read(folder.name) }.getOrNull()
        }?.let { if (it.state == "RUNNING" && !active) it.stopped("INTERRUPTED") else it }
    }

    /** Reads a single immutable checkpoint while assembling the destination without buffering PCM or the campaign. */
    fun export(manifest: VoiceBenchManifest, output: OutputStream) = synchronized(lock) {
        val folder = directory(manifest.runId)
        fun put(value: String) = output.write(value.toByteArray())
        fun copy(file: File) = file.inputStream().buffered().use { it.copyTo(output) }
        put("{\"suite\":")
        put(json.encodeToString(manifest))
        put(",\"liveSessionPerformance\":")
        copy(File(folder, "performance.json"))
        put(",\"stageEvidence\":[")
        manifest.results.forEachIndexed { index, result ->
            if (index > 0) put(",")
            put("{\"stageIndex\":$index,\"stageStatus\":\"${result.status.name}\",\"report\":")
            val path = File(folder, "stage-$index.json")
            if (path.isFile) {
                copy(path)
            } else {
                check(result.measurements == 0) { "Missing saved benchmark evidence" }
                put("null")
            }
            put("}")
        }
        put("]}")
    }

    private fun directory(id: String): File {
        require(id.matches(runId))
        return File(root, id)
    }

    private fun preferredDirectory(): File? =
        runCatching { File(root, "latest").readText() }.getOrNull()?.takeIf { it.matches(runId) }?.let(::directory)?.takeIf { it.isDirectory }

    private fun read(id: String) = json.decodeFromString<VoiceBenchManifest>(File(directory(id), "manifest.json").readText()).also { require(it.runId == id) }
    private fun runs() = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(runId) }.sortedByDescending { it.name }

    private fun atomic(file: File, write: (OutputStream) -> Unit) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val buffer = stream.buffered()
                write(buffer)
                buffer.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val lock = Any()
        private val runId = Regex("[0-9]{13}-[a-f0-9-]{36}")
    }
}
