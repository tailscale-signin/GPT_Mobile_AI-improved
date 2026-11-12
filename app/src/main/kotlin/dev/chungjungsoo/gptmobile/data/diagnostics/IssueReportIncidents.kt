package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Local projection for public reports. Raw messages, identifiers and OS logcat never enter it. */
internal object IssueReportIncidents {
    private const val RECENT_MILLIS = 60_000L
    private const val DUPLICATE_MILLIS = 3_000L
    private const val PENDING_LIMIT = 24
    private val lock = Any()
    private val events = ArrayDeque<Pair<Long, String>>()
    private data class FailureKey(
        val feature: ReportFeature,
        val code: String,
        val runId: String?,
        val invocationId: String?,
        val callId: String?,
        val jobId: String?,
        val activeJobId: String?
    )
    private val lastFailures = mutableMapOf<FailureKey, Long>()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingCorrelations = Channel<IssueReport>(PENDING_LIMIT)
    private val mutablePending = MutableStateFlow<List<IssueReport>>(emptyList())
    private val mutablePrivate = MutableStateFlow(false)
    val pending = mutablePending.asStateFlow()
    val privateSession = mutablePrivate.asStateFlow()

    @Volatile private var context: Context? = null

    @Volatile private var processState: SharedPreferences? = null

    @Volatile private var correlations: IssueReportCorrelationIndex? = null
    private val isPrivate: Boolean get() = mutablePrivate.value || AppLogRecorder.privateSessionActive

    init {
        persistenceScope.launch {
            for (report in pendingCorrelations) {
                try {
                    retainCorrelation(report)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Send and export retry this write and expose failure explicitly.
                }
            }
        }
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        correlations = null
    }

    private suspend fun correlationIndex(): IssueReportCorrelationIndex = withContext(Dispatchers.IO) {
        correlations ?: run {
            val owner = checkNotNull(context) { "Issue reporting is not initialized." }
            IssueReportCorrelationIndex(File(owner.noBackupFilesDir, "issue-report-correlations.json")).also {
                if (context === owner) correlations = it
            }
        }
    }

    /** Approved Send waits for this join before delivery; retries retain the same public UUID. */
    suspend fun retainCorrelation(report: IssueReport) {
        correlationIndex().retain(report)
    }

    suspend fun correlationSnapshot(): List<JsonObject> {
        if (isPrivate) return emptyList()
        val index = correlationIndex()
        pending.value.forEach { index.retain(it) }
        return index.snapshot().takeUnless { isPrivate }.orEmpty()
    }

    fun privacyChanged(active: Boolean) = synchronized(lock) {
        mutablePrivate.value = active
        if (active) clearRecent()
        processState?.edit()?.putBoolean("private", active)?.apply()
    }

    fun clearRecent() = synchronized(lock) {
        events.clear()
        lastFailures.clear()
    }

    fun recentEvents(): List<String> = synchronized(lock) {
        if (isPrivate) return emptyList()
        val now = System.nanoTime() / 1_000_000L
        events.filter { it.first >= now - RECENT_MILLIS }.map { (time, event) -> "-${(now - time).coerceAtLeast(0)}ms $event" }
    }

    fun log(tag: String, level: String, tracking: Boolean) = log(tag, level, tracking, null)

    fun log(tag: String, level: String, tracking: Boolean, code: String?, error: Throwable? = null, fields: Map<String, Any?> = emptyMap(), source: String? = null) {
        if (!LocalDiagnosticsPolicy.enabled && !tracking || isPrivate) return
        val severity = level.firstOrNull()?.takeIf { it in "DIWEF" } ?: return
        // Only application-owned tags with known meaning. Ignore raw logcat and the reporting pipeline.
        val feature = when (tag) {
            "Application", "Startup", "Database", "Download", "PluginSettings", "Rendering" -> ReportFeature.APP
            "Send", "Combined", "CombinedRetry", "Streaming", "Provider", "Network", "Recovery" -> ReportFeature.CHAT
            "VoiceEngine", "VoiceTiming", "VoiceRecognition", "VoicePlayback" -> ReportFeature.VOICE
            "Gateway", "GatewayQueue", "GatewayObserver", "GitHub", "GitHubExecution" -> ReportFeature.GATEWAY
            "LocalModel", "LocalImport", "LocalConnection", "LocalAssist" -> ReportFeature.LOCAL
            "Tools", "Tool", "Agent", "ToolCatalog", "MCP", "Research", "ResearchExamine" -> ReportFeature.TOOLS
            else -> return
        }
        if (severity == 'E' && LocalDiagnosticsPolicy.enabled && feature != ReportFeature.CHAT && feature != ReportFeature.VOICE) {
            val evidence = if (tag == "MCP") fields + ("operation" to "mcp") else fields
            val captureSource = source?.takeIf { it in IssueReportContext.sources } ?: "STRUCTURED_EVENT".takeIf { evidence.isNotEmpty() }
            val toolPrecheck = captureSource == "TOOL_RESULT" && IssueReportContext.fromFields("TOOL_RESULT", evidence).let { it.stage == "tool_precheck" && it.dispatched == false }
            failure(feature, IssueReportCodes.select(code, if (toolPrecheck) "TOOL_FAILURE" else "APP_ERROR"), error, evidence, captureSource)
        } else {
            synchronized(lock) {
                if (!isPrivate) addEvent(severity, feature, IssueReportCodes.known(code))
            }
        }
    }

    fun failure(feature: ReportFeature, code: String) = failure(feature, code, null)

    fun failure(feature: ReportFeature, code: String, error: Throwable?, fields: Map<String, Any?> = emptyMap(), source: String? = null) {
        if (!LocalDiagnosticsPolicy.enabled || isPrivate || context == null) return
        synchronized(lock) {
            if (!LocalDiagnosticsPolicy.enabled || isPrivate) return
            val now = System.nanoTime() / 1_000_000L
            val publicCode = IssueReportCodes.select(code, fallbackCode(feature))
            val evidence = IssueReportContext.fromFields(
                source ?: if (fields.isNotEmpty()) {
                    "STRUCTURED_EVENT"
                } else if (error == null) {
                    "CODE_ONLY"
                } else {
                    "EXCEPTION"
                },
                fields
            )
            val key = FailureKey(feature, publicCode, evidence.runIdHash, evidence.invocationIdHash, evidence.callIdHash, evidence.jobIdHash, evidence.activeJobIdHash)
            addEvent('E', feature, publicCode)
            if ((lastFailures[key] ?: Long.MIN_VALUE / 2) + DUPLICATE_MILLIS > now) return
            lastFailures.entries.removeAll { it.value < now - DUPLICATE_MILLIS }
            if (lastFailures.size >= 128) lastFailures.remove(lastFailures.minBy { it.value }.key)
            lastFailures[key] = now
            addPending(IssueReport.debug(feature, publicCode, recentEvents(), error, evidence))
        }
    }

    fun chatFailure(code: String?, rateLimited: Boolean) = chatFailure(code, rateLimited, null)

    fun chatFailure(code: String?, rateLimited: Boolean, error: Throwable?, fields: Map<String, Any?> = emptyMap(), invocationId: String? = null) {
        val localRecovery = IssueReportContext.localRecoveryCode(fields) != null
        val specificCode = IssueReportCodes.known(code)?.takeUnless { it == "CHAT_FAILED" }
            ?: "GITHUB_TASK_INCOMPLETE".takeIf {
                code in setOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED")
            }
        val observedRateLimit = if (!localRecovery && specificCode == null && !rateLimited) {
            val evidence = IssueReportContext.fromFields("CHAT_FAILURE", fields)
            evidence.provider != null &&
                evidence.stage in setOf("model_invocation", "provider_generation") &&
                evidence.httpStatus == 429 &&
                evidence.remoteAcceptance == "NO" &&
                evidence.requiresReconciliation != true
        } else {
            false
        }
        // A rejected provider attempt may lack a resumable checkpoint. Retain its
        // observed cause without treating the report as authorization to retry.
        val publicCode = if (localRecovery) "CHAT_FAILED" else specificCode ?: if (rateLimited || observedRateLimit) "PROVIDER_RATE_LIMITED" else "CHAT_FAILED"
        failure(ReportFeature.CHAT, publicCode, error, fields + listOfNotNull(invocationId?.let { "invocation_id" to it }).toMap(), "CHAT_FAILURE")
    }

    /** Called by the existing uncaught exception handler before it delegates to Android. */
    fun crash(error: Throwable) {
        if (!LocalDiagnosticsPolicy.enabled || isPrivate) return
        persist(IssueReport.debug(ReportFeature.APP, "JVM_CRASH", recentEvents(), error))
    }

    /** Called on IO as soon as the persisted Debug Mode value is available. */
    fun debugPreferenceChanged(enabled: Boolean) {
        val owner = context ?: return
        val current = processState ?: owner.getSharedPreferences("issue_report_process", Context.MODE_PRIVATE).also { saved ->
            if (enabled) {
                restoreFiles().forEach { report -> synchronized(lock) { addPending(report) } }
                runCatching { recoverProcessExit(owner, saved) }
            }
            processState = saved
        }
        if (!enabled) {
            synchronized(lock) {
                mutablePending.value = emptyList()
                if (!AppLogRecorder.enabled.value) clearRecent()
            }
            crashDirectory()?.listFiles()?.forEach { it.delete() }
        }
        val previousStart = current.getLong("debugSince", 0L)
        val since = if (enabled && current.getInt("pid", -1) == Process.myPid() && previousStart > 0) previousStart else System.currentTimeMillis()
        current.edit().putInt("pid", Process.myPid()).putLong("debugSince", if (enabled) since else 0L)
            .putBoolean("private", mutablePrivate.value).apply()
    }

    suspend fun dismiss(reportId: String) {
        synchronized(lock) { mutablePending.value = mutablePending.value.filterNot { it.reportId == reportId } }
        withContext(Dispatchers.IO) { crashFile(reportId)?.delete() }
    }

    private fun addEvent(level: Char, feature: ReportFeature, code: String? = null) {
        val line = "$level ${feature.name.lowercase()}" + (code?.let { " $it" } ?: "")
        if (events.size == IssueReport.EVENT_LIMIT) events.removeFirst()
        events.addLast(System.nanoTime() / 1_000_000L to line)
    }

    private fun addPending(report: IssueReport) {
        if (mutablePending.value.size < PENDING_LIMIT && mutablePending.value.none { it.reportId == report.reportId }) {
            mutablePending.value = (mutablePending.value + report).take(PENDING_LIMIT)
            // The queue is bounded; Send/export also retain every still-pending report synchronously.
            pendingCorrelations.trySend(report)
        }
    }

    private fun crashDirectory() = context?.let { File(it.noBackupFilesDir, "pending_issue_crashes") }
    private fun crashFile(id: String) = crashDirectory()?.let { AtomicFile(File(it, "$id.json")) }

    private fun persist(report: IssueReport) {
        val file = crashFile(report.reportId) ?: return
        val bytes = IssueReport.json.encodeToString(IssueReport.serializer(), report).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun restoreFiles(): List<IssueReport> {
        val files = crashDirectory()?.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
        files.drop(8).forEach { AtomicFile(it).delete() }
        return files.take(8).mapNotNull { file ->
            runCatching {
                AtomicFile(file).openRead().use { input ->
                    if (input.available() > 8192) return@use null
                    IssueReport.json.decodeFromString(IssueReport.serializer(), input.bufferedReader().readText())
                }
            }.getOrNull()?.takeIf { it.reportId == file.nameWithoutExtension && it.source == "debug" }
        }
    }

    private fun recoverProcessExit(owner: Context, saved: SharedPreferences) {
        val oldPid = saved.getInt("pid", -1)
        val since = saved.getLong("debugSince", 0L)
        if (oldPid <= 0 || oldPid == Process.myPid() || since <= 0 || saved.getBoolean("private", false)) return
        val manager = owner.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(owner.packageName, oldPid, 4).firstOrNull {
            it.timestamp >= since && it.processName == owner.packageName
        } ?: return
        val code = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_ANR -> "APP_NOT_RESPONDING"
            else -> return
        }
        val report = IssueReport.debug(ReportFeature.APP, code, emptyList(), context = IssueReportContext.fromFields("PROCESS_EXIT", occurredAtMillis = exit.timestamp))
        persist(report)
        synchronized(lock) { addPending(report) }
    }
}
