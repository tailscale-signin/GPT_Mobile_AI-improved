package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process
import dev.chungjungsoo.gptmobile.BuildConfig
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppLogEntry(val time: Long, val level: String, val tag: String, val message: String, val eventId: String = java.util.UUID.randomUUID().toString(), val monotonicNanos: Long = System.nanoTime(), val bootMillis: Long? = runCatching { android.os.SystemClock.elapsedRealtime() }.getOrNull(), val processId: Int = runCatching { Process.myPid() }.getOrDefault(-1), val processName: String? = runCatching { Application.getProcessName() }.getOrNull()) {
    fun line(): String {
        val identity = eventId.take(64)
        val prefix = "${Instant.ofEpochMilli(time)} ${level.take(1)}/${tag.take(64)}: eventId=$identity · monoNs=$monotonicNanos · bootMs=${bootMillis ?: "unknown"} · processId=$processId · processName=${processName?.take(256) ?: "unknown"} · "
        return boundedDiagnosticRecord(prefix, identity, message)
    }
}

/** Opt-in, app-process-only diagnostics. No network upload and no HTTP bodies. */
object AppLogRecorder {
    private const val QUEUE_RECORD_LIMIT = 512
    private const val RECENT_ENTRY_LIMIT = 2000
    private const val RECENT_CHARACTER_LIMIT = 256 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedRecords = java.util.concurrent.atomic.AtomicLong()
    private val droppedCriticalRecords = java.util.concurrent.atomic.AtomicLong()
    private val captureEpoch = java.util.concurrent.atomic.AtomicLong()
    private val preferenceRevision = java.util.concurrent.atomic.AtomicLong()
    private val preferenceWriteLock = Any()
    private val queue = BoundedDiagnosticPendingQueue<Pair<Long, AppLogEntry>>(maximumRecords = QUEUE_RECORD_LIMIT) { critical ->
        droppedRecords.incrementAndGet()
        if (critical) droppedCriticalRecords.incrementAndGet()
    }
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
    private val exportLock = Any()
    private val recent = ArrayDeque<AppLogEntry>()
    private var recentCharacters = 0
    private val coalescer = DiagnosticCoalescer()
    private val privateSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val privateSessionActive: Boolean get() = privateSessions.isNotEmpty()

    @Synchronized fun setPrivateSession(owner: String, active: Boolean) {
        if (active) {
            privateSessions.add(owner)
            captureEpoch.incrementAndGet()
            process?.destroy()
            process = null
            reader?.cancel()
            reader = null
            queue.clear()
            coalescer.clear()
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
            while (isActive) {
                queue.awaitRecords()
                synchronized(fileLock) {
                    if (!mutableEnabled.value || privateSessions.isNotEmpty()) {
                        queue.clear()
                        return@synchronized
                    }
                    // Drain under this lock, so a snapshot cannot miss a dequeued but unwritten batch.
                    val batch = queue.drainBatch()
                    var retained = false
                    // Persist one bounded record at a time, including lifecycle and tool failures.
                    // A large batch must not overrun the critical ring or allocate one giant string.
                    batch.forEach { (epoch, event) ->
                        if (epoch != captureEpoch.get()) return@forEach
                        runCatching { persistEntry(event) }
                            .onFailure { mutableError.value = "Unable to save logs. Check available storage." }
                        retainRecent(event)
                        retained = true
                    }
                    if (retained && publication?.isActive != true) {
                        publication = scope.launch {
                            kotlinx.coroutines.delay(250)
                            synchronized(fileLock) {
                                mutableEntries.value = if (mutableEnabled.value && privateSessions.isEmpty()) recent.toList() else emptyList()
                            }
                        }
                    }
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
            override fun onActivityDestroyed(activity: Activity) {
                FrameTimingRecorder.stop(activity)
                event(activity, "destroyed")
            }
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
        val revision = preferenceRevision.incrementAndGet()
        val owner = app
        scope.launch {
            synchronized(preferenceWriteLock) {
                if (preferenceRevision.get() == revision) {
                    owner?.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE)?.edit()?.putBoolean("tracking", value)?.apply()
                }
            }
        }
        if (!value) {
            process?.destroy()
            process = null
            reader?.cancel()
            reader = null
            captureEpoch.incrementAndGet()
            queue.clear()
            coalescer.clear()
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
            checkpoint("AndroidBuild", androidBuildDiagnostic())
        }
    }

    fun record(tag: String, message: String, level: String = "I") {
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        val epoch = captureEpoch.get()
        coalescer.accept(tag.take(64), diagnosticMessageForStorage(message), level).forEach { (eventTag, eventMessage, eventLevel) ->
            if (!mutableEnabled.value || privateSessions.isNotEmpty() || epoch != captureEpoch.get()) return@forEach
            val entry = AppLogEntry(System.currentTimeMillis(), eventLevel.take(1), eventTag, eventMessage)
            val characters = entry.message.length + entry.tag.length + entry.level.length + entry.processName.orEmpty().length + 256
            queue.offer(epoch to entry, characters, criticalDiagnosticRecord(entry.tag, entry.message, entry.level))
        }
    }

    /** IO-thread startup markers survive an abort before the async log queue drains. */
    internal fun checkpoint(tag: String, message: String, level: String = "I") {
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        val epoch = captureEpoch.get()
        val entry = AppLogEntry(System.currentTimeMillis(), level, tag.take(64), diagnosticMessageForStorage(message))
        synchronized(fileLock) {
            if (!mutableEnabled.value || privateSessions.isNotEmpty() || epoch != captureEpoch.get()) return
            retainRecent(entry)
            mutableEntries.value = recent.toList()
            runCatching {
                val line = entry.line()
                appendCritical(line)
                append(line, durable = true)
            }
                .onFailure { mutableError.value = "Unable to save logs. Check available storage." }
        }
    }

    fun clear() {
        scope.launch {
            app?.let(DiagnosticCaptureStorage::clear)
            RunDiagnostics.journal?.clear()
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.clear()
            synchronized(exportLock) {
                synchronized(fileLock) {
                    // Invalidate producers that began sanitizing a record before this clear.
                    captureEpoch.incrementAndGet()
                    queue.clear()
                    recent.clear()
                    recentCharacters = 0
                    coalescer.clear()
                    droppedRecords.set(0)
                    droppedCriticalRecords.set(0)
                    mutableEntries.value = emptyList()
                    runCatching {
                        directory()?.listFiles()?.forEach { it.delete() }
                        app?.let { File(it.cacheDir, "diagnostics").listFiles()?.forEach { file -> file.deleteRecursively() } }
                    }
                }
            }
        }
    }

    /** IO only. Copies bounded, immutable files; callers own and close the returned snapshot. */
    fun captureForExport(directory: File, reducedMemory: Boolean = false): DiagnosticLogSnapshot = synchronized(exportLock) {
        val capturedAt = System.currentTimeMillis()
        val owner = app
        if (owner == null || privateSessions.isNotEmpty()) {
            return@synchronized DiagnosticLogSnapshot(
                emptyList(),
                capturedAt,
                mutableEnabled.value,
                droppedRecords.get(),
                if (owner == null) "NOT_INITIALIZED" else "PRIVATE_SESSION",
                droppedCriticalRecords = droppedCriticalRecords.get()
            )
        }
        check(directory.mkdirs() || directory.isDirectory)
        val snapshotDirectory = File(directory, "log-snapshot-${java.util.UUID.randomUUID()}")
        check(snapshotDirectory.mkdir())
        try {
            synchronized(fileLock) snapshot@{
                if (privateSessions.isNotEmpty()) {
                    return@snapshot DiagnosticLogSnapshot(
                        emptyList(),
                        capturedAt,
                        mutableEnabled.value,
                        droppedRecords.get(),
                        "PRIVATE_SESSION",
                        snapshotDirectory = snapshotDirectory,
                        droppedCriticalRecords = droppedCriticalRecords.get()
                    )
                }
                val failures = mutableMapOf<String, String>()
                if (mutableEnabled.value) {
                    coalescer.drain().forEach { (tag, message, level) ->
                        runCatching { persistEntry(AppLogEntry(System.currentTimeMillis(), level, tag, message)) }
                            .onFailure {
                                failures["pending-records"] = "SOURCE_WRITE_FAILED"
                                mutableError.value = "Unable to save logs. Check available storage."
                            }
                    }
                    var drained = 0
                    while (drained++ < QUEUE_RECORD_LIMIT) {
                        val pending = queue.poll() ?: break
                        if (pending.first == captureEpoch.get()) {
                            runCatching { persistEntry(pending.second) }
                                .onFailure {
                                    failures["pending-records"] = "SOURCE_WRITE_FAILED"
                                    mutableError.value = "Unable to save logs. Check available storage."
                                }
                        }
                    }
                }
                val sources = listOf("critical-previous.log", "critical.log", "previous.log", "current.log").mapNotNull { name ->
                    val source = File(owner.filesDir, "diagnostics/$name")
                    if (!source.isFile) return@mapNotNull null
                    val target = File(snapshotDirectory, name)
                    val limit = when {
                        name.startsWith("critical") -> DiagnosticLogFiles.CRITICAL_FILE_BYTES
                        reducedMemory -> DiagnosticLogFiles.REDUCED_RECENT_FILE_BYTES
                        else -> DiagnosticLogFiles.RECENT_FILE_BYTES
                    }
                    try {
                        DiagnosticLogFiles.copyTail(source, target, limit)
                    } catch (_: java.io.IOException) {
                        target.delete()
                        failures[name] = "SOURCE_READ_FAILED"
                        null
                    }
                }
                val omission = if (sources.any { it.recordCount > 0 }) {
                    null
                } else if (failures.isNotEmpty()) {
                    failures.values.first()
                } else if (!mutableEnabled.value) {
                    "TRACKING_DISABLED_NO_RETAINED_LOGS"
                } else {
                    "NO_RETAINED_LOGS"
                }
                DiagnosticLogSnapshot(sources, capturedAt, mutableEnabled.value, droppedRecords.get(), omission, failures, snapshotDirectory, droppedCriticalRecords = droppedCriticalRecords.get())
            }
        } catch (failure: Throwable) {
            snapshotDirectory.deleteRecursively()
            throw failure
        }
    }

    /** Only one app-log conversion runs at a time; histories remain on disk throughout. */
    fun export(structured: Boolean = false): File? = DiagnosticExportGate.acquire().use {
        synchronized(exportLock) {
            val context = app ?: return@synchronized null
            if (privateSessions.isNotEmpty()) return@synchronized null
            val folder = File(context.cacheDir, "diagnostics").also { it.mkdirs() }
            // Independent exports cannot overwrite a file still being shared/read.
            val target = File.createTempFile("app-diagnostics-", if (structured) ".jsonl" else ".log", folder)
            try {
                captureForExport(folder).use { snapshot ->
                    val exportedAt = System.currentTimeMillis()
                    val incidents = DiagnosticIncidentTracker()
                    val duplicateReferences = DiagnosticExportDeduplicator()
                    target.bufferedWriter().use { output ->
                        if (structured) output.appendLine(structuredDiagnosticLine("${Instant.now()} I/Export: version=${BuildConfig.VERSION_NAME} · build=${BuildConfig.VERSION_CODE} · package=${BuildConfig.APPLICATION_ID} · appCommit=${BuildConfig.APP_COMMIT} · variant=${BuildConfig.BUILD_TYPE} · droppedRecords=${droppedRecords.get()} · vendor_log_available=false · process=${Process.myPid()}"))
                        val buildMetadata = "${Instant.now()} I/AndroidBuild: ${androidBuildDiagnostic()}"
                        output.appendLine(if (structured) structuredDiagnosticLine(buildMetadata) else buildMetadata)
                        historicalExitMetadata(context).forEach { line ->
                            output.appendLine(if (structured) structuredDiagnosticLine(line, "android_exit_history", exportedAtMillis = exportedAt) else line)
                        }
                        val logAvailability = "${Instant.ofEpochMilli(snapshot.capturedAtMillis)} I/DiagnosticLogCapture: retainedRecords=${snapshot.recordCount} · retainedBytes=${snapshot.byteCount} · trackingEnabled=${snapshot.trackingEnabled} · omissionReason=${snapshot.omissionReason ?: "none"} · sourceFailures=${snapshot.sourceFailures.size} · droppedRecords=${snapshot.droppedRecords} · droppedCriticalRecords=${snapshot.droppedCriticalRecords} · duplicateTracking=BOUNDED_WINDOW"
                        output.appendLine(if (structured) structuredDiagnosticLine(logAvailability) else logAvailability)
                        val seen = BoundedDiagnosticIdentityWindow()
                        dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot().forEach { failure ->
                            val line = providerFailureLine(failure)
                            val identity = diagnosticEventIdentity(line)
                            val association = incidents.accept(line, identity, null)
                            output.appendLine(if (structured) structuredDiagnosticLine(line, "provider_failure", duplicate = !seen.add(identity), captureIdentity = identity, incidentId = association.id, incidentRoot = association.first, exportedAtMillis = exportedAt) else line)
                        }
                        LocalRuntimeIncidentHistory.snapshot().forEach { incident ->
                            val line = runtimeIncidentLine(incident)
                            val identity = diagnosticEventIdentity(line)
                            val association = incidents.accept(line, identity, null)
                            output.appendLine(if (structured) structuredDiagnosticLine(line, "runtime_incident_history", duplicate = !seen.add(identity), captureIdentity = identity, incidentId = association.id, incidentRoot = association.first, exportedAtMillis = exportedAt) else line)
                        }
                        snapshot.sources.forEach { sourceFile ->
                            val source = sourceFile.name
                            val cursor = DiagnosticCaptureCursor(source, seen::add)
                            sourceFile.file.bufferedReader().useLines { lines ->
                                lines.forEachIndexed { index, line ->
                                    val record = cursor.accept(line, index)
                                    val incident = incidents.accept(line, record.identity, record.parent)
                                    if (structured) {
                                        val duplicate = duplicateReferences.accept(line, record.identity, source, index)
                                        duplicate.batch?.let(output::appendLine)
                                        if (duplicate.duplicate) return@forEachIndexed
                                    }
                                    output.appendLine(if (structured) structuredDiagnosticLine(line, source, record.duplicate, record.parent, record.sequence, record.identity, incident.id, incident.first, exportedAtMillis = exportedAt) else line)
                                }
                            }
                        }
                        if (structured) duplicateReferences.flush()?.let(output::appendLine)
                        if (seen.evictedRecords > 0) {
                            val limits = "${Instant.ofEpochMilli(exportedAt)} I/DiagnosticLogCapture: duplicateTracking=BOUNDED_WINDOW · identitiesEvicted=${seen.evictedRecords}"
                            output.appendLine(if (structured) structuredDiagnosticLine(limits) else limits)
                        }
                    }
                }
                target
            } catch (failure: Throwable) {
                target.delete()
                throw failure
            }
        }
    }

    private fun androidBuildDiagnostic(): String {
        fun field(value: String) = value.replace(Regex("[^A-Za-z0-9._:/ -]"), "_").take(256)
        return "manufacturer=${field(android.os.Build.MANUFACTURER)} · model=${field(android.os.Build.MODEL)} · sdk=${android.os.Build.VERSION.SDK_INT} · androidRelease=${field(android.os.Build.VERSION.RELEASE)} · buildId=${field(android.os.Build.ID)} · buildFingerprint=${field(android.os.Build.FINGERPRINT)} · source=public_build_api · protected_properties_read=false"
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

    private fun retainRecent(entry: AppLogEntry) {
        recent.addLast(entry)
        recentCharacters += entry.message.length + entry.tag.length
        while (recent.size > RECENT_ENTRY_LIMIT || recentCharacters > RECENT_CHARACTER_LIMIT) {
            val removed = recent.removeFirst()
            recentCharacters -= removed.message.length + removed.tag.length
        }
    }

    private fun persistEntry(entry: AppLogEntry) {
        val line = entry.line()
        if (criticalDiagnosticRecord(entry.tag, entry.message, entry.level)) appendCritical(line)
        append(line)
    }

    private fun appendCritical(line: String) {
        val folder = directory() ?: return
        BoundedDiagnosticLogStore(folder, "critical.log", "critical-previous.log", DiagnosticLogFiles.CRITICAL_FILE_BYTES)
            .append(line, durable = true)
    }

    private fun directory(): File? = app?.let { File(it.filesDir, "diagnostics").also { folder -> folder.mkdirs() } }
    private fun append(line: String, durable: Boolean = false) {
        val folder = directory() ?: return
        BoundedDiagnosticLogStore(folder, "current.log", "previous.log", DiagnosticLogFiles.RECENT_FILE_BYTES)
            .append(line, durable)
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
