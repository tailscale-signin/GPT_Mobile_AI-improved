package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
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
}
