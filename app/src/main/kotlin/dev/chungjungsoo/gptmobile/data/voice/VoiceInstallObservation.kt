package dev.chungjungsoo.gptmobile.data.voice

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class VoiceTransferStage { CHECKING, CONNECTING, TRANSFERRING, VERIFYING, EXTRACTING, READY, FAILED }

enum class VoiceAssetFailureKind { DNS, TIMEOUT, TLS, HTTP, NETWORK_IO, INTEGRITY, STORAGE, MODEL_IN_USE, UNKNOWN }

internal class VoiceAssetException(
    val kind: VoiceAssetFailureKind,
    message: String,
    val httpStatus: Int? = null
) : IOException(message)

internal fun voiceAssetFailureKind(failure: Exception): VoiceAssetFailureKind = when (failure) {
    is VoiceAssetException -> failure.kind
    is UnknownHostException -> VoiceAssetFailureKind.DNS
    is SocketTimeoutException -> VoiceAssetFailureKind.TIMEOUT
    is SSLException -> VoiceAssetFailureKind.TLS
    else -> VoiceAssetFailureKind.UNKNOWN
}

internal fun voiceDownloadException(failure: IOException): VoiceAssetException = if (failure is VoiceAssetException) {
    failure
} else {
    val kind = voiceAssetFailureKind(failure).takeUnless { it == VoiceAssetFailureKind.UNKNOWN } ?: VoiceAssetFailureKind.NETWORK_IO
    VoiceAssetException(
        kind,
        when (kind) {
            VoiceAssetFailureKind.DNS -> "Could not resolve the voice download server. Check your connection and try again."
            VoiceAssetFailureKind.TIMEOUT -> "The voice download connection timed out. Try again to resume."
            VoiceAssetFailureKind.TLS -> "Could not establish a secure connection to the voice download server."
            else -> "The voice download connection was interrupted. Try again to resume."
        }
    )
}

/** Only a fresh process may adopt an already scheduled owner. A cancelled or
 * replaced attempt cannot reclaim progress or terminal state through a late callback. */
internal class VoiceInstallAttempt {
    @Volatile private var current: String? = null
    private var canRestore = true
    private val activeTransfers = mutableMapOf<String, Int>()

    @Synchronized fun begin(owner: String) {
        current = owner
        canRestore = false
    }

    @Synchronized fun restore(owner: String): Boolean {
        if (current == owner) return true
        if (!canRestore) return false
        begin(owner)
        return true
    }

    @Synchronized fun cancel() {
        current = null
        canRestore = false
    }

    fun owns(owner: String): Boolean = current == owner

    @Synchronized fun acquireTransfer(owner: String): Boolean {
        if (current != owner) return false
        activeTransfers[owner] = (activeTransfers[owner] ?: 0) + 1
        return true
    }

    @Synchronized fun releaseTransfer(owner: String) {
        val count = activeTransfers[owner] ?: return
        if (count <= 1) activeTransfers.remove(owner) else activeTransfers[owner] = count - 1
    }

    @Synchronized fun hasActiveTransfer(): Boolean = current?.let { (activeTransfers[it] ?: 0) > 0 } == true

    @Synchronized fun hasActiveTransfer(owner: String): Boolean = current == owner && (activeTransfers[owner] ?: 0) > 0

    @Synchronized fun runIfCurrent(owner: String?, block: () -> Unit): Boolean {
        if (owner != null && current != owner) return false
        block()
        return true
    }
}

internal data class VoiceInstallTransfer(
    val pack: String,
    val stage: VoiceTransferStage?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val failureKind: VoiceAssetFailureKind? = null,
    val httpStatus: Int? = null
)

internal data class VoiceInstallState(
    val phase: String = "IDLE",
    val failure: String? = null,
    val pendingReason: String? = null,
    val stopReason: Int? = null,
    val progress: Int? = null,
    val elapsedMillis: Long? = null,
    val transfer: VoiceInstallTransfer? = null
) {
    val pending: Boolean get() = phase in setOf("QUEUED", "RUNNING", "WAITING_NETWORK")

    fun diagnosticFields(scheduler: String): Map<String, Any?> = buildMap {
        put("diagnostic_area", "VOICE")
        put("diagnostic_capture_mode", "SELECTED_CAPTURE")
        put("voice_install_state", phase)
        put("voice_install_scheduler", scheduler)
        failure?.let { put("voice_install_failure", it) }
        pendingReason?.let { put("voice_install_pending_reason", it) }
        stopReason?.let { put("voice_install_stop_reason", it) }
        progress?.takeIf { it in 0..100 }?.let { put("voice_install_progress_percent", it) }
        elapsedMillis?.takeIf { it in 0..16_000_000 }?.let { put("voice_install_elapsed_ms", it) }
        transfer?.let { observed ->
            put("voice_install_pack", observed.pack)
            observed.stage?.let { put("voice_install_transfer_stage", it.name) }
            observed.failureKind?.let { put("voice_install_failure_kind", it.name) }
            observed.httpStatus?.takeIf { it in 100..599 }?.let { put("voice_install_http_status", it) }
            observed.downloadedBytes.takeIf { it in 0..1_000_000_000 }?.let { put("voice_install_downloaded_bytes", it) }
            observed.totalBytes.takeIf { it in 1..1_000_000_000 }?.let { put("voice_install_total_bytes", it) }
        }
    }

    fun waitingMessage(): String = when (pendingReason) {
        "CONNECTIVITY" -> "Waiting for an internet connection…"
        "BACKGROUND_RESTRICTION", "APP_STANDBY" -> "Android has restricted this download. Check the app's battery and background settings."
        "DEVICE_STATE" -> "Android is waiting for device conditions to improve before downloading."
        "QUOTA", "SCHEDULER_OPTIMIZATION" -> "Android has queued this download. It will start when the scheduler allows it."
        "USER" -> "Android has paused this job after a user action. Cancel it to start a new download."
        "APP_STATE" -> "Android cannot run the download service in the app's current state."
        else -> if (phase == "RUNNING") "Preparing voice files…" else "Download queued · waiting for Android…"
    }
}
