package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

internal object NativeExitEvidence {
    fun journal(context: Context, directory: File): NativeOperationJournal = NativeOperationJournal(directory, { since, pid ->
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val exit = runCatching { manager?.getHistoricalProcessExitReasons(context.packageName, pid, 8)?.firstOrNull { it.timestamp >= since && it.processName.endsWith(":local_inference") } }.getOrNull()
        val termination = exit?.takeIf { it.reason == ApplicationExitInfo.REASON_SIGNALED && it.status == android.system.OsConstants.SIGKILL }?.let { NativeWorkerTermination.matched(File(context.noBackupFilesDir, "native-worker"), pid, since, it.timestamp) }
        val category = if (exit?.reason == ApplicationExitInfo.REASON_SIGNALED) {
            nativeSignalExitCategory(exit.status, termination)
        } else when (exit?.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            else -> "UNKNOWN"
        }
        if (exit == null) {
            category
        } else {
            val memory = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                "pssBytes=${exit.pss.coerceAtLeast(0) * 1024L},rssBytes=${exit.rss.coerceAtLeast(0) * 1024L}"
            } else {
                "pssBytes=unavailable,rssBytes=unavailable"
            }
            val description = dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(exit.description.orEmpty()).take(160).replace('|', ' ').replace('\n', ' ')
            "$category|status=${exit.status}|$memory|importance=${exit.importance}|description=$description"
        }
    }, android.os.Process::myPid)
}

/** A SIGKILL without a matching app termination receipt is evidence, but never proof of OOM. */
internal fun nativeSignalExitCategory(status: Int, matchedTermination: String?): String =
    matchedTermination ?: if (status == 9) "SIGKILL_UNATTRIBUTED" else "SIGNALLED_UNATTRIBUTED"
