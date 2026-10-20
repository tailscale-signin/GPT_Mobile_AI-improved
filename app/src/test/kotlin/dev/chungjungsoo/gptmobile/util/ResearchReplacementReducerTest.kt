package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReplacementReducerTest {
    @Test fun fallbackReplacesAnIncompleteWordInsteadOfAppendingToIt() = runBlocking {
        val old = "The evidence concerns ex-smo"
        val next = "A partial evidence summary with source links."
        val change = ApiState.ResearchReplacement(researchHash(old), next)
        var visible = ""
        flow {
            emit(ApiState.Success(old))
            emit(change)
            emit(ApiState.Done)
        }.collectApiStateUpdates(onUpdate = { text, _, _ -> visible = text }, onResearchReplacement = { digest, _ -> digest == researchHash(visible) })
        assertEquals(next, visible)
        assertTrue(change.acknowledgement.await())
    }
}
