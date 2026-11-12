package dev.chungjungsoo.gptmobile.data.diagnostics

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable

/** Only an explicitly approved report enters this durable outbox; retries reuse its exact bytes. */
internal class IssueReportOutbox(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    enum class State { QUEUED, SENDING, SENT, FAILED }

    @Serializable
    data class Entry(
        val reportId: String,
        val payload: String,
        val title: String? = null,
        val feature: String? = null,
        val reportErrorCode: String? = null,
        val state: State = State.QUEUED,
        val issueNumber: Int? = null,
        val failureCode: String? = null,
        val httpStatus: Int? = null,
        val retryable: Boolean = true,
        val retryNotBeforeMillis: Long = 0,
        val automaticAttempts: Int = 0,
        val storedAtMillis: Long,
        val updatedAtMillis: Long
    ) {
        val receiptKey: String get() = "$reportId:$updatedAtMillis:${state.name}"
    }

    fun approve(report: IssueReport, payload: String = report.approvedPayload()): Entry = synchronized(lock) {
        require(payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES) { "Report exceeds the submission limit" }
        require(IssueReport.json.decodeFromString(IssueReport.serializer(), payload) == report) { "Approved report identity changed" }
        val existing = load(report.reportId)
        if (existing != null) {
            check(existing.payload == payload) { "Approved report payload changed" }
            return@synchronized existing
        }
        val known = entries()
        // An unreadable entry is still retained approved work and consumes capacity.
        check(reportIds().size - known.count { it.state == State.SENT } < MAX_PENDING_REPORTS) { CAPACITY_MESSAGE }
        val time = now()
        Entry(report.reportId, payload, report.title, report.feature, report.errorCode, storedAtMillis = time, updatedAtMillis = time).also(::save)
    }

    fun load(reportId: String): Entry? = synchronized(lock) {
        val file = file(reportId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        file.openRead().use { input ->
            check(input.channel.size() <= MAX_STORED_BYTES) { "Saved report exceeds the storage limit" }
            val bytes = input.readBytes()
            check(bytes.size <= MAX_STORED_BYTES) { "Saved report exceeds the storage limit" }
            IssueReport.json.decodeFromString(Entry.serializer(), bytes.decodeToString()).also {
                check(it.reportId == reportId && it.payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES) { "Saved report identity changed" }
                val report = IssueReport.json.decodeFromString(IssueReport.serializer(), it.payload)
                check(report.reportId == reportId) { "Saved payload identity changed" }
                check(it.state != State.SENT || (it.issueNumber ?: 0) > 0) { "Saved report receipt is invalid" }
            }
        }
    }

    fun entries(): List<Entry> = synchronized(lock) {
        reportIds().mapNotNull { id -> runCatching { load(id) }.getOrNull() }.sortedByDescending { it.storedAtMillis }
    }

    fun queue(reportId: String, manualRetry: Boolean = false): Entry = update(reportId) {
        if (it.state == State.SENT || !it.retryable) it else it.copy(state = State.QUEUED, automaticAttempts = if (manualRetry) 0 else it.automaticAttempts)
    }

    fun sending(reportId: String): Entry = update(reportId) { it.copy(state = State.SENDING, automaticAttempts = it.automaticAttempts + 1) }

    fun sent(reportId: String, number: Int): Entry = synchronized(lock) {
        val result = update(reportId) {
            require(number > 0)
            it.copy(state = State.SENT, issueNumber = number, failureCode = null, httpStatus = null, retryable = false, retryNotBeforeMillis = 0)
        }
        // Keep a small receipt history; never prune queued, uncertain or rejected reports.
        entries().filter { it.state == State.SENT }.drop(MAX_CONFIRMED_REPORTS).forEach { file(it.reportId).delete() }
        result
    }

    fun removeFailed(reportId: String) = synchronized(lock) {
        check(load(reportId)?.state == State.FAILED) { "Only a stopped report can be removed" }
        file(reportId).delete()
    }

    fun failed(reportId: String, failure: IssueReportSubmission.Failure, automaticRetry: Boolean): Entry = update(reportId) {
        it.copy(
            state = if (automaticRetry) State.QUEUED else State.FAILED,
            failureCode = failure.code,
            httpStatus = failure.httpStatus,
            retryable = failure.retryable,
            retryNotBeforeMillis = maxOf(it.retryNotBeforeMillis, failure.retryNotBeforeMillis ?: 0)
        )
    }

    private fun update(reportId: String, transform: (Entry) -> Entry): Entry = synchronized(lock) {
        val previous = checkNotNull(load(reportId)) { "Approved report is unavailable" }
        transform(previous).copy(updatedAtMillis = maxOf(now(), previous.updatedAtMillis + 1)).also(::save)
    }

    private fun save(entry: Entry) {
        check(directory.isDirectory || directory.mkdirs()) { "Report storage is unavailable" }
        val file = file(entry.reportId)
        val bytes = IssueReport.json.encodeToString(Entry.serializer(), entry).encodeToByteArray()
        require(bytes.size <= MAX_STORED_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun file(reportId: String): AtomicFile {
        require(REPORT_ID.matches(reportId)) { "Invalid report identity" }
        return AtomicFile(File(directory, "$reportId.json"))
    }

    private fun reportIds(): List<String> = directory.listFiles().orEmpty().mapNotNull { file ->
        file.name.removeSuffix(".bak").takeIf { it.endsWith(".json") }?.removeSuffix(".json")?.takeIf(REPORT_ID::matches)
    }.distinct()

    companion object {
        private val lock = Any()
        private val REPORT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private const val MAX_STORED_BYTES = 32 * 1024
        internal const val MAX_PENDING_REPORTS = 24
        internal const val MAX_CONFIRMED_REPORTS = 24
        internal const val CAPACITY_MESSAGE = "Report storage is full. Review or remove saved reports before sending another."
        fun forContext(context: Context) = IssueReportOutbox(File(context.noBackupFilesDir, "approved_issue_reports"))
    }
}
