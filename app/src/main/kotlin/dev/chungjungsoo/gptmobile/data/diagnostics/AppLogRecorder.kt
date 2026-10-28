package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppLogEntry(val time: Long, val level: String, val tag: String, val message: String, val eventId: String = java.util.UUID.randomUUID().toString(), val monotonicNanos: Long = System.nanoTime(), val bootMillis: Long? = runCatching { android.os.SystemClock.elapsedRealtime() }.getOrNull(), val processId: Int = runCatching { Process.myPid() }.getOrDefault(-1), val processName: String? = runCatching { Application.getProcessName() }.getOrNull()) {
    fun line(): String {
        val lines = message.lines()
        return "${Instant.ofEpochMilli(time)} $level/$tag: eventId=$eventId · monoNs=$monotonicNanos · bootMs=${bootMillis ?: "unknown"} · processId=$processId · processName=${processName ?: "unknown"} · ${lines.first()}" +
            lines.drop(1).mapIndexed { index, line -> "\nparentEventId=$eventId · sequence=${index + 1} · $line" }.joinToString("")
    }
}

/** Opt-in, app-process-only diagnostics. No network upload and no HTTP bodies. */
object AppLogRecorder {
    private const val QUEUE_CAPACITY = 2048
    private const val RECENT_ENTRY_LIMIT = 2000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedRecords = java.util.concurrent.atomic.AtomicLong()
    private val queue = Channel<AppLogEntry>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { droppedRecords.incrementAndGet() })
    private val mutableEnabled = MutableStateFlow(false)
    private val mutableEntries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val enabled = mutableEnabled.asStateFlow()
    val entries = mutableEntries.asStateFlow()
    val error = mutableError.asStateFlow()

    @Volatile private var app: Context? = null
    private var reader: Job? = null

    @Volatile private var process: java.lang.Process? = null
    private val fileLock = Any()
    private val recent = ArrayDeque<AppLogEntry>()
    private val privateSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Synchronized fun setPrivateSession(owner: String, active: Boolean) {
        if (active) {
            privateSessions.add(owner)
            process?.destroy()
            process = null
            reader?.cancel()
            reader = null
            synchronized(fileLock) { while (queue.tryReceive().isSuccess) Unit }
            if (mutableEnabled.value) mutableError.value = "Logging is paused for a temporary conversation. Toggle tracking after leaving it to restart Android log capture."
        } else {
            privateSessions.remove(owner)
        }
        RunDiagnostics.journal?.privateSession = privateSessions.isNotEmpty()
    }

    @Synchronized fun initialize(application: Application) {
        if (app != null) return
        app = application.applicationContext
        LocalRuntimeIncidentHistory.initialize(application)
        dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.configureReceiptDirectory(File(application.noBackupFilesDir, "model-verification"))
        dev.chungjungsoo.gptmobile.data.localmodel.GgufImportAdapter.configureMetadataCache(File(application.noBackupFilesDir, "gguf-metadata"))
        RunDiagnostics.journal = RunDiagnosticJournal(
            dev.chungjungsoo.gptmobile.data.security.AndroidSecretVault(application, "gptmobile-diagnostic-metadata-v1", "run_diagnostics_v1"),
            scope
        )
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                // Keep sanitized method frames, including framework-only failures, even
                // when the async queue cannot drain. Exception messages stay private.
                runCatching {
                    checkpoint("AppCrash", crashDiagnosticSummary(failure, thread.name), level = "F")
                }
                previousHandler.uncaughtException(thread, failure)
            }
        }
        scope.launch {
            var publication: Job? = null
            for (entry in queue) {
                synchronized(fileLock) {
                    if (!mutableEnabled.value || privateSessions.isNotEmpty()) return@synchronized
                    val batch = mutableListOf(entry)
                    repeat(255) { queue.tryReceive().getOrNull()?.let(batch::add) }
                    batch.forEach(recent::addLast)
                    while (recent.size > RECENT_ENTRY_LIMIT) recent.removeFirst()
                    if (publication?.isActive != true) {
                        publication = scope.launch {
                            kotlinx.coroutines.delay(250)
                            synchronized(fileLock) {
                                mutableEntries.value = if (mutableEnabled.value && privateSessions.isEmpty()) recent.toList() else emptyList()
                            }
                        }
                    }
                    runCatching { append(batch.joinToString("\n") { it.line() }) }.onFailure { mutableError.value = "Unable to save logs. Check available storage." }
                }
                // Drain persistence continuously. Delaying the consumer caused dropped
                // records during bursts; only UI publication is throttled above.
            }
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = event(activity, "created")
            override fun onActivityStarted(activity: Activity) = event(activity, "started")
            override fun onActivityResumed(activity: Activity) {
                event(activity, "resumed")
                FrameTimingRecorder.start(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                FrameTimingRecorder.stop(activity)
                event(activity, "paused")
            }
            override fun onActivityStopped(activity: Activity) = event(activity, "stopped")
            override fun onActivityDestroyed(activity: Activity) = event(activity, "destroyed")
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            private fun event(activity: Activity, state: String) = record("Lifecycle", "${activity.javaClass.simpleName} $state")
        })
        scope.launch { setEnabled(application.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE).getBoolean("tracking", false)) }
        record("Application", "Started process ${Process.myPid()}")
    }

    @Synchronized fun setEnabled(value: Boolean) {
        mutableEnabled.value = value
        RunDiagnostics.journal?.enabled = value
        FrameTimingRecorder.refresh()
        app?.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE)?.edit()?.putBoolean("tracking", value)?.apply()
        if (!value) {
            process?.destroy()
            process = null
            reader?.cancel()
            reader = null
            return
        }
        if (reader?.isActive == true || app == null || privateSessions.isNotEmpty()) return
        mutableError.value = null
        reader = scope.launch {
            var running: java.lang.Process? = null
            try {
                running = ProcessBuilder("logcat", "--pid=${Process.myPid()}", "-v", "brief", "-T", java.time.LocalDateTime.now().plusNanos(1_000_000).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))).redirectErrorStream(true).start()
                process = running
                if (!mutableEnabled.value) {
                    running.destroy()
                    return@launch
                }
                running.inputStream.bufferedReader().use { stream ->
                    while (isActive && mutableEnabled.value) {
                        val line = stream.readLine() ?: break
                        if (isKnownAndroidDiagnosticNoise(line)) continue
                        val level = line.firstOrNull()?.toString()?.takeIf { it in setOf("V", "D", "I", "W", "E", "F") } ?: "I"
                        record("Android", line, level)
                    }
                }
                if (mutableEnabled.value) mutableError.value = "Android log stream ended. App event tracking continues; toggle tracking to reconnect."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutableEnabled.value) mutableError.value = "Android log stream unavailable on this device. App event tracking continues."
            } finally {
                running?.destroy()
                if (process === running) process = null
            }
        }
        record("Diagnostics", "Log tracking enabled")
        // Read historical ANR stacks only on IO, never while constructing the UI.
        scope.launch { captureHistoricalAnrSummaries() }
        scope.launch {
            checkpoint("Application", "version=${BuildConfig.VERSION_NAME} · versionCode=${BuildConfig.VERSION_CODE} · package=${BuildConfig.APPLICATION_ID} · appCommit=${BuildConfig.APP_COMMIT} · variant=${BuildConfig.BUILD_TYPE} · droppedRecords=${droppedRecords.get()} · vendor_log_available=false · process=${Process.myPid()}")
        }
    }

    fun record(tag: String, message: String, level: String = "I") {
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        queue.trySend(AppLogEntry(System.currentTimeMillis(), level, tag.take(64), diagnosticMessageForStorage(message)))
    }

    /** IO-thread startup markers survive an abort before the async log queue drains. */
    internal fun checkpoint(tag: String, message: String, level: String = "I") {
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        val entry = AppLogEntry(System.currentTimeMillis(), level, tag.take(64), diagnosticMessageForStorage(message))
        synchronized(fileLock) {
            if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
            recent.addLast(entry)
            while (recent.size > RECENT_ENTRY_LIMIT) recent.removeFirst()
            mutableEntries.value = recent.toList()
            runCatching {
                append(entry.line(), durable = true)
                appendCritical(entry.line())
            }
                .onFailure { mutableError.value = "Unable to save logs. Check available storage." }
        }
    }

    fun clear() {
        scope.launch {
            RunDiagnostics.journal?.clear()
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.clear()
            synchronized(fileLock) {
                while (queue.tryReceive().isSuccess) Unit
                recent.clear()
                droppedRecords.set(0)
                mutableEntries.value = emptyList()
                runCatching {
                    directory()?.listFiles()?.forEach { it.delete() }
                    app?.let { File(it.cacheDir, "diagnostics").listFiles()?.forEach { file -> file.delete() } }
                }
            }
        }
    }

    /** Snapshot bytes under the writer lock; JSON parsing and export IO never hold it. */
    fun export(structured: Boolean = false): File? {
        val context = app ?: return null
        val snapshot = synchronized(fileLock) {
            val folder = directory() ?: return null
            val pending = buildList {
                repeat(QUEUE_CAPACITY) {
                    val entry = queue.tryReceive().getOrNull() ?: return@repeat
                    if (mutableEnabled.value && privateSessions.isEmpty()) add(entry.line())
                }
            }
            if (pending.isNotEmpty()) append(pending.joinToString("\n"))
            listOf("critical-previous.log", "critical.log", "previous.log", "current.log").map { File(folder, it) }
                .filter { it.isFile }.map { it.name to it.readBytes() }
        }
        val folder = File(context.cacheDir, "diagnostics").also { it.mkdirs() }
        // Independent exports cannot overwrite a file still being shared/read.
        val target = File.createTempFile("app-diagnostics-", if (structured) ".jsonl" else ".log", folder)
        try {
            val exportedAt = System.currentTimeMillis()
            val incidents = DiagnosticIncidentTracker()
            target.bufferedWriter().use { output ->
                if (structured) output.appendLine(structuredDiagnosticLine("${Instant.now()} I/Export: version=${BuildConfig.VERSION_NAME} · build=${BuildConfig.VERSION_CODE} · package=${BuildConfig.APPLICATION_ID} · appCommit=${BuildConfig.APP_COMMIT} · variant=${BuildConfig.BUILD_TYPE} · droppedRecords=${droppedRecords.get()} · vendor_log_available=false · process=${Process.myPid()}"))
                historicalExitMetadata(context).forEach { line ->
                    output.appendLine(if (structured) structuredDiagnosticLine(line, "android_exit_history", exportedAtMillis = exportedAt) else line)
                }
                dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot().forEach { failure ->
                    val line = providerFailureLine(failure)
                    val identity = diagnosticEventIdentity(line)
                    val association = incidents.accept(line, identity, null)
                    output.appendLine(if (structured) structuredDiagnosticLine(line, "provider_failure", captureIdentity = identity, incidentId = association.id, incidentRoot = association.first, exportedAtMillis = exportedAt) else line)
                }
                LocalRuntimeIncidentHistory.snapshot().forEach { incident ->
                    val line = runtimeIncidentLine(incident)
                    val identity = diagnosticEventIdentity(line)
                    val association = incidents.accept(line, identity, null)
                    output.appendLine(if (structured) structuredDiagnosticLine(line, "runtime_incident_history", captureIdentity = identity, incidentId = association.id, incidentRoot = association.first, exportedAtMillis = exportedAt) else line)
                }
                val seen = mutableSetOf<String>()
                var parent: String? = null
                var sequence = 0
                snapshot.forEach { (source, bytes) ->
                    bytes.inputStream().bufferedReader().useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            val root = Regex("^\\S+ [VDIWEF]/[^:]+: ").containsMatchIn(line)
                            val explicit = Regex("(?:^|: | · )eventId=([a-zA-Z0-9-]+)").find(line)?.groupValues?.get(1)
                            if (root) {
                                parent = explicit ?: "$source:$index"
                                sequence = 0
                            } else {
                                Regex("(?:^| · )parentEventId=([a-zA-Z0-9-]+)").find(line)?.groupValues?.get(1)?.let { parent = it }
                                sequence = Regex("(?:^| · )sequence=([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: (sequence + 1)
                            }
                            val identity = if (root) parent!! else "${parent ?: source}:$sequence"
                            val incident = incidents.accept(line, identity, parent.takeUnless { root })
                            output.appendLine(if (structured) structuredDiagnosticLine(line, source, !seen.add(identity), parent.takeUnless { root }, sequence, identity, incident.id, incident.first, exportedAtMillis = exportedAt) else line)
                        }
                    }
                }
            }
            return target
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    /** Capture only allow-listed main-thread stack frames, never raw ANR traces or private text. */
    private fun captureHistoricalAnrSummaries() {
        val context = app ?: return
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val preferences = context.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE)
            val lastTimestamp = preferences.getLong("last_sanitized_anr", 0L)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.reason == android.app.ApplicationExitInfo.REASON_ANR && it.timestamp > lastTimestamp }
                .sortedBy { it.timestamp }
            for (exit in exits) {
                if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
                val frames = exit.traceInputStream?.bufferedReader()?.use { reader ->
                    mainThreadAnrFrames(generateSequence { reader.readLine() })
                }.orEmpty()
                record(
                    "AnrTraceSummary",
                    "exitTime=${exit.timestamp} · mainAppFrames=${frames.size} · trace=${if (frames.isEmpty()) "not_available_or_no_app_frames" else "sanitized"}" +
                        if (frames.isEmpty()) "" else "\n" + frames.joinToString("\n"),
                    "W"
                )
                preferences.edit().putLong("last_sanitized_anr", exit.timestamp).apply()
            }
        }.onFailure { record("AnrTraceSummary", "coverage=unavailable · reason=trace_access_failed", "W") }
    }

    /** Bounded platform metadata and app-owned process names; no traces or system descriptions. */
    private fun historicalExitMetadata(context: Context): List<String> {
        val prefix = "${Instant.now()} I/ProcessExit: "
        if (android.os.Build.VERSION.SDK_INT < 30) return listOf(prefix + "coverage=unsupported")
        return try {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val exits = manager?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                ?: return listOf(prefix + "coverage=unavailable")
            if (exits.isEmpty()) {
                listOf(prefix + "coverage=no_records · reason=unknown")
            } else {
                exits.map { exit ->
                    "${Instant.ofEpochMilli(exit.timestamp)} I/ProcessExit: coverage=available · exitTime=${exit.timestamp} · occurredAtMillis=${exit.timestamp} · processId=${exit.pid} · processName=${exit.processName?.takeIf { it == context.packageName || it.startsWith(context.packageName + ":") } ?: "unknown"} · workerExitConfirmed=true · workerExitEvidence=ANDROID_APPLICATION_EXIT_INFO · reasonCode=${exit.reason} · statusCode=${exit.status} · cause=${if (exit.reason == android.app.ApplicationExitInfo.REASON_SIGNALED) {
                        "UNKNOWN_CAUSE"
                    } else if (exit.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY) {
                        "OS_LOW_MEMORY"
                    } else {
                        "OS_REASON_CODE"
                    }} · importance=${exit.importance} · pssKb=${exit.pss} · rssKb=${exit.rss}"
                }
            }
        } catch (_: Exception) {
            listOf(prefix + "coverage=unavailable · reason=unknown")
        }
    }

    private fun appendCritical(line: String) {
        val folder = directory() ?: return
        val current = File(folder, "critical.log")
        if (current.length() > 128 * 1024) {
            val previous = File(folder, "critical-previous.log")
            previous.delete()
            check(current.renameTo(previous))
        }
        FileOutputStream(current, true).use { output ->
            output.write((line + "\n").encodeToByteArray())
            output.fd.sync()
        }
    }

    private fun directory(): File? = app?.let { File(it.filesDir, "diagnostics").also { folder -> folder.mkdirs() } }
    private fun append(line: String, durable: Boolean = false) {
        val directory = directory() ?: return
        val current = File(directory, "current.log")
        if (current.length() > 1_000_000) {
            val previous = File(directory, "previous.log")
            previous.delete()
            check(current.renameTo(previous))
        }
        FileOutputStream(current, true).use { output ->
            output.write((line + "\n").encodeToByteArray())
            if (durable) output.fd.sync()
        }
    }
}

internal fun isKnownAndroidDiagnosticNoise(line: String): Boolean {
    val normalized = line.lowercase()
    return KNOWN_ANDROID_NOISE.any(normalized::contains)
}

private val KNOWN_ANDROID_NOISE = listOf(
    "windowonbackdispatcher",
    "d/adpflog",
    "d/insetscontroller",
    "sendcancelifrunning",
    "imetracker",
    "image decoding logging dropped",
    "frame time is",
    "in the future",
    "unrecognized profile/level",
    "nosupport [codec.profilelevel",
    "unsupported profile",
    "codec2client query -- param skipped"
)

internal fun redactLogMessage(message: String): String {
    var text = DiagnosticRedactor.redact(redactDiagnosticPayload(message))
    text = text.replace(Regex("""(?:/data/|/storage/|/sdcard/|/home/)[^\s,;]+"""), "[private-path]")
    text = text.replace(Regex("""\b(?:10\.(?:\d{1,3}\.){2}\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3})\b"""), "[private-address]")
    if (
        "ChatRoomV2(" in text &&
        Regex("""(?i)(?:^|\s)D/chats\b|\bchats\s*[:=]""").containsMatchIn(text)
    ) {
        return "D/chats: [redacted chat collection]"
    }
    // Android Log.d calls from legacy/UI code may stringify entire Room/domain objects.
    // Keep diagnostics useful without exporting conversation titles, profile UUIDs,
    // message text or timestamps embedded in those data-class dumps.
    listOf("ChatRoomV2", "MessageV2", "PlatformV2").forEach { type ->
        text = text.replace(Regex("""\b$type\([^\r\n)]*\)"""), "$type([redacted])")
    }
    // Match complete quoted keys as well as raw headers; keep JSON siblings and
    // escaping valid so safe provider records remain structurally exportable.
    text = text.replace(Regex("""(?i)("(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-goog-api-key|x-subscription-token|mcp-session-id|anthropic-organization-id|anthropic-workspace-id|openai-organization|openai-project|x-organization-id|x-workspace-id|api_?key|access_?token|refresh_?token|password|client_?secret)"\s*:\s*)"(?:\\.|[^"\\])*"""")) { match -> "${match.groupValues[1]}\"[redacted]\"" }
    text = text.replace(Regex("(?i)(?<![A-Za-z0-9_-])(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-goog-api-key|x-subscription-token|mcp-session-id|anthropic-organization-id|anthropic-workspace-id|openai-organization|openai-project|x-organization-id|x-workspace-id)\\s*[:=]\\s*[^\\r\\n]+"), "$1: [redacted]")
    text = text.replace(Regex("(?i)(bearer|basic)\\s+[a-z0-9._~+/=-]+"), "$1 [redacted]")
    text = text.replace(Regex("(?i)([\\\"]?(?:api_?key|access_?token|refresh_?token|password|client_?secret)[\\\"]?\\s*[:=]\\s*[\\\"]?)[^\\\"\\s,}]+"), "$1[redacted]")
    text = text.replace(Regex("\\b(?:sk-|hf_|ghp_|github_pat_)[A-Za-z0-9_-]{8,}"), "[redacted]")
    return text
}
