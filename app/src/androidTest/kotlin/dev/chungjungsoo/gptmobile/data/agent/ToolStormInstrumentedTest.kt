package dev.chungjungsoo.gptmobile.data.agent

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Capture a bounded stress stimulus for same-device Perfetto/frame/heap comparisons. */
@RunWith(AndroidJUnit4::class)
class ToolStormInstrumentedTest {
    @Test fun threeThousandCallProviderIsStoppedAtIngress() = runBlocking {
        val before = Debug.getPss()
        var produced = 0
        var uiEvents = 0
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    repeat(3000) {
                        produced++
                        emit(ProviderEvent.ToolCall("c$it", "web_search", buildJsonObject {}))
                    }
                } else {
                    emit(ProviderEvent.TextDelta("No invalid batch was executed."))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        AgentRunner(AgentRunLimits(maxToolCalls = 50)).run(session, emptyList()).collect { uiEvents++ }
        assertTrue(produced <= 33)
        assertTrue(uiEvents < 64)
        Log.i("ToolStormQualification", "produced=$produced uiEvents=$uiEvents pssBeforeKb=$before pssAfterKb=${Debug.getPss()} configuredStimulus=3000")
    }
}
