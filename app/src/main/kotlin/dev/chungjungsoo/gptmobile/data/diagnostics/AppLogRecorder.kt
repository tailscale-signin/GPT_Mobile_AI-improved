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

data class AppLogEntry(val time: Long, val level: String, val tag: String, val message: String) {
    fun line() = "${Instant.ofEpochMilli(time)} $level/$tag: $message"
}

/** Opt-in, app-process-only diagnostics. No network upload and no HTTP bodies. */
object AppLogRecorder {
    private const val QUEUE_CAPACITY = 512
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<AppLogEntry>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
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
    }

    @Synchronized fun initialize(application: Application) {
        if (app != null) return
        app = application.applicationContext
        scope.launch {
            for (entry in queue) {
                synchronized(fileLock) {
                    if (!mutableEnabled.value || privateSessions.isNotEmpty()) return@synchronized
                    recent.addLast(entry)
                    while (recent.size > 500) recent.removeFirst()
                    mutableEntries.value = recent.toList()
                    runCatching { append(entry.line()) }.onFailure { mutableError.value = "Unable to save logs. Check available storage." }
                }
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
        setEnabled(application.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE).getBoolean("tracking", false))
        record("Application", "Started process ${Process.myPid()}")
    }

    @Synchronized fun setEnabled(value: Boolean) {
        mutableEnabled.value = value
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
        scope.launch {
            checkpoint("Application", "version=${BuildConfig.VERSION_NAME} · versionCode=${BuildConfig.VERSION_CODE} · package=${BuildConfig.APPLICATION_ID} · process=${Process.myPid()}")
        }
    }

    fun record(tag: String, message: String, level: String = "I") {
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        queue.trySend(AppLogEntry(System.currentTimeMillis(), level, tag.take(64), redactLogMessage(message).take(8000)))
    }

    /** IO-thread startup markers survive an abort before the async log queue drains. */
    internal fun checkpoint(tag: String, message: String) {
        if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
        val entry = AppLogEntry(System.currentTimeMillis(), "I", tag.take(64), redactLogMessage(message).take(8000))
        synchronized(fileLock) {
            if (!mutableEnabled.value || privateSessions.isNotEmpty()) return
            recent.addLast(entry)
            while (recent.size > 500) recent.removeFirst()
            mutableEntries.value = recent.toList()
            runCatching { append(entry.line(), durable = true) }
                .onFailure { mutableError.value = "Unable to save logs. Check available storage." }
        }
    }

    fun clear() {
        scope.launch {
            synchronized(fileLock) {
                while (queue.tryReceive().isSuccess) Unit
                recent.clear()
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
            listOf("previous.log", "current.log").map { File(folder, it) }
                .filter { it.isFile }.map { it.readBytes() }
        }
        val folder = File(context.cacheDir, "diagnostics").also { it.mkdirs() }
        // Independent exports cannot overwrite a file still being shared/read.
        val target = File.createTempFile("app-diagnostics-", if (structured) ".jsonl" else ".log", folder)
        try {
            target.bufferedWriter().use { output ->
                if (structured) output.appendLine(structuredDiagnosticLine("${Instant.now()} I/Export: version=${BuildConfig.VERSION_NAME} · build=${BuildConfig.VERSION_CODE} · package=${BuildConfig.APPLICATION_ID} · process=${Process.myPid()}"))
                snapshot.forEach { bytes ->
                    bytes.inputStream().bufferedReader().useLines { lines ->
                        lines.forEach { output.appendLine(if (structured) structuredDiagnosticLine(it) else it) }
                    }
                }
            }
            return target
        } catch (failure: Exception) {
            target.delete()
            throw failure
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
    var text = DiagnosticRedactor.redact(message)
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
    text = text.replace(Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-goog-api-key|x-subscription-token|mcp-session-id)\\s*[:=]\\s*[^\\r\\n]+"), "$1: [redacted]")
    text = text.replace(Regex("(?i)(bearer|basic)\\s+[a-z0-9._~+/=-]+"), "$1 [redacted]")
    text = text.replace(Regex("(?i)([\\\"]?(?:api_?key|access_?token|refresh_?token|password|client_?secret)[\\\"]?\\s*[:=]\\s*[\\\"]?)[^\\\"\\s,}]+"), "$1[redacted]")
    text = text.replace(Regex("\\b(?:sk-|hf_|ghp_|github_pat_)[A-Za-z0-9_-]{8,}"), "[redacted]")
    return text
}
