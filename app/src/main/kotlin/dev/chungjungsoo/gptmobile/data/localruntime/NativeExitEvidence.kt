package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

internal object NativeExitEvidence {
    fun journal(context: Context, directory: File): NativeOperationJournal = NativeOperationJournal(directory, { since, pid ->
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val exit = runCatching { manager?.getHistoricalProcessExitReasons(context.packageName, pid, 8)?.firstOrNull { it.timestamp >= since && it.processName.endsWith(":local_inference") } }.getOrNull()
        when (exit?.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            else -> "UNKNOWN"
        }
    }, android.os.Process::myPid)
}
