package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.ApplicationExitInfo

/** The operating system's exit reason is evidence about this exit, not a nearby logged exception. */
internal data class AndroidProcessExitEvidence(val name: String, val cause: String, val crashConfirmed: Boolean?)

internal fun androidProcessExitEvidence(reason: Int): AndroidProcessExitEvidence = when (reason) {
    ApplicationExitInfo.REASON_USER_REQUESTED -> AndroidProcessExitEvidence("REASON_USER_REQUESTED", "USER_REQUESTED", false)
    ApplicationExitInfo.REASON_USER_STOPPED -> AndroidProcessExitEvidence("REASON_USER_STOPPED", "USER_STOPPED", false)
    ApplicationExitInfo.REASON_EXIT_SELF -> AndroidProcessExitEvidence("REASON_EXIT_SELF", "PROCESS_EXIT_SELF", false)
    ApplicationExitInfo.REASON_CRASH -> AndroidProcessExitEvidence("REASON_CRASH", "OS_REPORTED_JAVA_CRASH", true)
    ApplicationExitInfo.REASON_CRASH_NATIVE -> AndroidProcessExitEvidence("REASON_CRASH_NATIVE", "OS_REPORTED_NATIVE_CRASH", true)
    ApplicationExitInfo.REASON_ANR -> AndroidProcessExitEvidence("REASON_ANR", "OS_REPORTED_ANR", false)
    ApplicationExitInfo.REASON_LOW_MEMORY -> AndroidProcessExitEvidence("REASON_LOW_MEMORY", "OS_LOW_MEMORY", false)
    ApplicationExitInfo.REASON_SIGNALED -> AndroidProcessExitEvidence("REASON_SIGNALED", "UNKNOWN_CAUSE", null)
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> AndroidProcessExitEvidence("REASON_PERMISSION_CHANGE", "PERMISSION_CHANGE", false)
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> AndroidProcessExitEvidence("REASON_INITIALIZATION_FAILURE", "INITIALIZATION_FAILURE", null)
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> AndroidProcessExitEvidence("REASON_DEPENDENCY_DIED", "DEPENDENCY_EXIT", null)
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> AndroidProcessExitEvidence("REASON_EXCESSIVE_RESOURCE_USAGE", "EXCESSIVE_RESOURCE_USAGE", false)
    ApplicationExitInfo.REASON_OTHER -> AndroidProcessExitEvidence("REASON_OTHER", "OS_REASON_CODE", null)
    else -> AndroidProcessExitEvidence("REASON_UNKNOWN", "UNKNOWN_CAUSE", null)
}
