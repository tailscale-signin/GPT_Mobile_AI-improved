package dev.chungjungsoo.gptmobile.data.voice

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInstallObservationTest {
    @Test fun queuedObservationDoesNotInventNetworkTrafficOrAnHttpResponse() {
        val fields = VoiceInstallState("QUEUED", pendingReason = "DEVICE_STATE", elapsedMillis = 35_000).diagnosticFields("JOB_SCHEDULER")
        assertEquals("DEVICE_STATE", fields["voice_install_pending_reason"])
        assertEquals(35_000L, fields["voice_install_elapsed_ms"])
        assertFalse(fields.containsKey("voice_install_downloaded_bytes"))
        assertFalse(fields.containsKey("voice_install_total_bytes"))
        assertFalse(fields.containsKey("voice_install_http_status"))
        assertFalse(fields.containsKey("voice_install_failure_kind"))
    }

    @Test fun retainedTransferEvidenceHasExactBytesAndClosedFailureMetadata() {
        val fields = VoiceInstallState(
            "FAILED",
            "ASSET_FAILURE",
            transfer = VoiceInstallTransfer("KOKORO", VoiceTransferStage.CONNECTING, 45_233_659, 177_587_069, VoiceAssetFailureKind.HTTP, 503)
        ).diagnosticFields("JOB_SCHEDULER")
        assertEquals("KOKORO", fields["voice_install_pack"])
        assertEquals("CONNECTING", fields["voice_install_transfer_stage"])
        assertEquals(45_233_659L, fields["voice_install_downloaded_bytes"])
        assertEquals(177_587_069L, fields["voice_install_total_bytes"])
        assertEquals(503, fields["voice_install_http_status"])
        assertEquals("HTTP", fields["voice_install_failure_kind"])
    }

    @Test fun invalidAndUnavailableMeasurementsAreOmittedInsteadOfClamped() {
        val fields = VoiceInstallState(
            "RUNNING",
            elapsedMillis = 16_000_001,
            transfer = VoiceInstallTransfer("MOONSHINE", VoiceTransferStage.CONNECTING, -1, 0, httpStatus = 0)
        ).diagnosticFields("JOB_SCHEDULER")
        assertFalse(fields.containsKey("voice_install_elapsed_ms"))
        assertFalse(fields.containsKey("voice_install_downloaded_bytes"))
        assertFalse(fields.containsKey("voice_install_total_bytes"))
        assertFalse(fields.containsKey("voice_install_http_status"))
    }

    @Test fun transferFailuresUseTheirTypeWithoutLeakingPrivateExceptionText() {
        for ((failure, kind) in listOf(
            UnknownHostException("private-host") to VoiceAssetFailureKind.DNS,
            SocketTimeoutException("private-path") to VoiceAssetFailureKind.TIMEOUT,
            SSLException("private-certificate") to VoiceAssetFailureKind.TLS,
            IOException("private-query") to VoiceAssetFailureKind.NETWORK_IO
        )) {
            val observed = voiceDownloadException(failure)
            assertEquals(kind, observed.kind)
            assertFalse(observed.message.orEmpty().contains("private"))
            assertEquals(null, observed.httpStatus)
        }
        // A generic IO failure outside the known network operations is not
        // evidence that the network, rather than local storage, failed.
        assertEquals(VoiceAssetFailureKind.UNKNOWN, voiceAssetFailureKind(IOException("storage-or-network")))
    }

    @Test fun knownHttpFailureKeepsTheActualStatus() {
        val failure = VoiceAssetException(VoiceAssetFailureKind.HTTP, "Server refused the request", 429)
        assertTrue(failure === voiceDownloadException(failure))
        assertEquals(429, voiceDownloadException(failure).httpStatus)
    }

    @Test fun oldAttemptCannotOverwriteANewerQueuedRunOrRestoreAfterCancellation() {
        val owner = VoiceInstallAttempt()
        var phase = "QUEUED"
        owner.begin("first")
        assertTrue(owner.runIfCurrent("first") { phase = "RUNNING" })
        owner.cancel()
        assertFalse(owner.restore("first"))
        owner.begin("second")
        phase = "QUEUED"
        assertFalse(owner.runIfCurrent("first") { phase = "FAILED" })
        assertFalse(owner.restore("first"))
        assertEquals("QUEUED", phase)
        assertTrue(owner.runIfCurrent("second") { phase = "RUNNING" })
        assertEquals("RUNNING", phase)
    }

    @Test fun aFreshProcessCanAdoptOnlyOneObservedScheduledAttempt() {
        val owner = VoiceInstallAttempt()
        assertTrue(owner.restore("persisted"))
        assertTrue(owner.restore("persisted"))
        assertFalse(owner.restore("unrelated"))
        assertTrue(owner.owns("persisted"))
    }

    @Test fun anOldTransferStillUnwindingCannotMakeTheReplacementActive() {
        val owner = VoiceInstallAttempt()
        owner.begin("old")
        assertTrue(owner.acquireTransfer("old"))
        assertTrue(owner.hasActiveTransfer())
        owner.cancel()
        owner.begin("queued-replacement")
        assertFalse(owner.hasActiveTransfer())
        assertFalse(owner.hasActiveTransfer("old"))
        assertFalse(owner.acquireTransfer("old"))
        owner.releaseTransfer("old")
        assertFalse(owner.hasActiveTransfer())
        assertTrue(owner.owns("queued-replacement"))
    }

    @Test fun overlappingTransfersForTheSameOwnerReleaseOnlyTheirOwnActivity() {
        val owner = VoiceInstallAttempt()
        owner.begin("current")
        assertTrue(owner.acquireTransfer("current"))
        assertTrue(owner.acquireTransfer("current"))
        owner.releaseTransfer("unrelated")
        owner.releaseTransfer("current")
        assertTrue(owner.hasActiveTransfer())
        assertTrue(owner.hasActiveTransfer("current"))
        owner.releaseTransfer("current")
        assertFalse(owner.hasActiveTransfer())
        owner.releaseTransfer("current")
        assertFalse(owner.hasActiveTransfer())
    }

    @Test fun queuedReasonsDoNotClaimThatBytesAreDownloading() {
        val offline = VoiceInstallState("WAITING_NETWORK", pendingReason = "CONNECTIVITY")
        assertEquals("Waiting for an internet connection…", offline.waitingMessage())
        val restricted = VoiceInstallState("QUEUED", pendingReason = "BACKGROUND_RESTRICTION")
        assertTrue(restricted.waitingMessage().contains("background settings"))
        val unspecified = VoiceInstallState("QUEUED")
        assertEquals("Download queued · waiting for Android…", unspecified.waitingMessage())
    }
}
