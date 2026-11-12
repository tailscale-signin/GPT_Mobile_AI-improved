package dev.chungjungsoo.gptmobile.data.diagnostics

import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keeps the public UUID join even after a prompt is dismissed, without copying user report text. */
internal class IssueReportCorrelationIndex(private val file: File) {
    @Serializable
    private data class Entry(
        val reportId: String,
        val source: String,
        val version: String,
        val build: Int,
        val commit: String,
        val retainedAtMillis: Long,
        val feature: String? = null,
        val errorCode: String? = null,
        val context: IssueReportContext? = null
    )

    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private val serializer = ListSerializer(Entry.serializer())

    suspend fun retain(report: IssueReport) = withContext(Dispatchers.IO) {
        require(report.reportId.matches(REPORT_ID)) { "Report identity is invalid." }
        report.approvedPayload()
        mutex.withLock {
            val previous = read()
            val existing = previous.firstOrNull { it.reportId == report.reportId }
            val entry = Entry(report.reportId, report.source, report.version, report.build, report.commit, existing?.retainedAtMillis ?: System.currentTimeMillis(), report.feature, report.errorCode, report.context)
            check(existing == null || existing == entry) { "A saved report identity cannot change its incident evidence." }
            if (existing != null) return@withLock
            write((previous + entry).takeLast(MAX_ENTRIES))
        }
    }

    suspend fun snapshot(): List<JsonObject> = withContext(Dispatchers.IO) {
        mutex.withLock {
            read().map { entry ->
                buildJsonObject {
                    put("kind", "issue_report_correlation")
                    put("scope", "APP_REPORT_HISTORY_NOT_CONVERSATION_ATTRIBUTION")
                    put("reportId", entry.reportId)
                    put("source", entry.source)
                    put("version", entry.version)
                    put("build", entry.build)
                    put("commit", entry.commit)
                    put("retainedAtMillis", entry.retainedAtMillis)
                    entry.feature?.let { put("feature", it) }
                    entry.errorCode?.let { put("errorCode", it) }
                    entry.context?.let { put("context", IssueReport.json.encodeToJsonElement(IssueReportContext.serializer(), it)) }
                    put("evidenceAvailability", if (entry.context == null) "LEGACY_CONTEXT_NOT_RETAINED" else "INCIDENT_CONTEXT_PROJECTED")
                }
            }
        }
    }

    private fun read(): List<Entry> {
        if (!file.isFile && !File(file.path + ".bak").isFile) return emptyList()
        check(maxOf(file.length(), File(file.path + ".bak").length()) <= MAX_BYTES) { "Report correlation index exceeds its bounded capacity." }
        return AtomicFile(file).openRead().use { input ->
            IssueReport.json.decodeFromString(serializer, input.bufferedReader().readText())
        }.also { entries ->
            check(entries.size <= MAX_ENTRIES && entries.map { it.reportId }.distinct().size == entries.size && entries.all { it.reportId.matches(REPORT_ID) }) { "Report correlation index has invalid identities." }
        }
    }

    private fun write(entries: List<Entry>) {
        val bytes = IssueReport.json.encodeToString(serializer, entries).encodeToByteArray()
        check(bytes.size <= MAX_BYTES) { "Report correlation index exceeds its bounded capacity." }
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_BYTES = 256 * 1024L
        private val REPORT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
