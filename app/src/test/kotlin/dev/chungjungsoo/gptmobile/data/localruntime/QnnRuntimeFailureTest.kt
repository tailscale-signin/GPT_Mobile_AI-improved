package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnRuntimeFailureTest {
    @Test
    fun `linker failure retains its stage and receives linker code`() {
        val failure = classifyQnnFailure(
            QnnRuntimeStage.NATIVE_LINK,
            UnsatisfiedLinkError("dlopen failed: undefined symbol")
        )

        assertEquals(QnnRuntimeStage.NATIVE_LINK, failure.stage)
        assertEquals(QnnFailureCode.ELF_LINK_FAILURE, failure.code)
    }

    @Test
    fun `unknown native message is not guessed to be a DSP failure`() {
        val failure = classifyQnnFailure(QnnRuntimeStage.ENGINE_INIT, IllegalStateException("QNN could not start"))

        assertEquals(QnnFailureCode.UNKNOWN_NATIVE_ERROR, failure.code)
    }

    @Test fun quarantineAndUnsafeBytesAreNotGenericModelErrors() {
        val quarantined = classifyQnnFailure(QnnRuntimeStage.CRASH_JOURNAL, IllegalStateException("This artifact/runtime/backend was interrupted inside native code"))
        assertEquals(QnnFailureCode.PREVIOUS_NATIVE_CRASH, quarantined.code)
        assertEquals(QnnRuntimeStage.CRASH_JOURNAL, quarantined.stage)
        assertEquals(QnnFailureCode.UNSAFE_ARTIFACT, classifyQnnFailure(QnnRuntimeStage.ENGINE_INIT, IllegalStateException("Model package checksum mismatch")).code)
    }

    @Test fun nativeDetailIsBoundedAndRedactedWhileOriginalCauseSurvives() {
        val cause = IllegalStateException("dlopen /data/user/0/app/secret.so Authorization: Bearer topsecret")
        val failure = classifyQnnFailure(QnnRuntimeStage.ENGINE_INIT, cause)
        assertTrue(failure.cause === cause)
        assertTrue(!failure.nativeDetail.contains("topsecret"))
        assertTrue(!failure.nativeDetail.contains("/data/user"))
        assertTrue(failure.nativeDetail.length <= 640)
        assertThrows(OutOfMemoryError::class.java) { classifyQnnFailure(QnnRuntimeStage.ENGINE_INIT, OutOfMemoryError()) }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { classifyQnnFailure(QnnRuntimeStage.ENGINE_INIT, kotlinx.coroutines.CancellationException()) }
    }
}
