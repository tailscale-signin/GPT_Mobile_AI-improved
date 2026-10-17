package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

internal object NativeExitEvidence {
    fun journal(context: Context, directory: File): NativeOperationJournal = NativeOperationJournal(directory, { since, pid ->
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val exit = runCatching { manager?.getHistoricalProcessExitReasons(context.packageName, pid, 8)?.firstOrNull { it.timestamp >= since && it.processName.endsWith(":local_inference") } }.getOrNull()
        val category = when (exit?.reason) {
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
