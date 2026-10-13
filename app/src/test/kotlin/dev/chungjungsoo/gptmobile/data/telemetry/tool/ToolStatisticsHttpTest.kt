package dev.chungjungsoo.gptmobile.data.telemetry.tool

import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolStatisticsHttpTest {
    @Test fun recordingDeadlineDoesNotFailOrRepeatToolExecution() = runBlocking {
        val repository = ToolStatisticsRepository(ApplicationProvider.getApplicationContext(), 0, 0)
        var executions = 0
        val tool = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("deadline_fixture", "Fixture", kotlinx.serialization.json.JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): dev.chungjungsoo.gptmobile.data.agent.AgentToolResult {
                executions++
                return dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(callId, dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text("fixture result"), false)
            }
        }
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(), statistics = repository)
        val result = budget.bind(tool).execute("call", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(false, result.isError)
        assertEquals(1, executions)
        assertEquals(true, repository.coverageGap.value)
    }

    @Test fun physicalRetriesDoNotMultiplyLogicalUsesAndResetRejectsReplayedStart() = runBlocking {
        val repository = ToolStatisticsRepository(ApplicationProvider.getApplicationContext(), 5000, 5000)
        repository.dao.subjects() // Finish Room initialization before the bounded observer starts.
        val identity = ToolIdentity.of("web_search")
        val id = requireNotNull(repository.begin(identity, "profile", "run", "CLIENT", "call"))
        var sends = 0
        val client = HttpClient(
            MockEngine {
                sends++
                respond("fixture", if (sends == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
            }
        ) {
            install(ToolStatisticsHttpPlugin)
            install(HttpRequestRetry) {
                maxRetries = 1
                retryIf { _, response -> response.status.value >= 500 }
                delayMillis { 0 }
            }
        }
        try {
            withContext(ToolStatisticsContext(repository, id, identity.key, "profile", "run")) { client.get("https://example.org/fixture") }
            repository.complete(id, ToolOutcome.SUCCESS, ToolDelivery.PENDING, null, 10, false)
            val attempts = repository.dao.attempts().filter { it.invocation == id }
            assertEquals(2, sends)
            assertEquals(2, attempts.size)
            assertEquals(setOf("FAILURE", "SUCCESS"), attempts.map { it.outcome }.toSet())
            assertEquals(1L, repository.dao.rollup(identity.key, -1, "profile")?.total)
            assertEquals("PENDING", repository.dao.invocation(id)?.delivery)
            ToolDeliveryReceipt().also { it.pending(id, repository) }.acknowledge()
            assertEquals("DELIVERED", repository.dao.invocation(id)?.delivery)
            val calculator = dev.chungjungsoo.gptmobile.data.agent.tool.CalculatorTool()
            val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits(), statistics = repository, statisticsProfile = "primary", statisticsRoot = "child-run")
            budget.bind(calculator).forStatisticsProfile("helper").execute("calculate", kotlinx.serialization.json.buildJsonObject { put("expression", kotlinx.serialization.json.JsonPrimitive("1+2")) })
            val calculatorIdentity = ToolIdentity.of(calculator.definition.name)
            assertEquals(1L, repository.dao.rollup(calculatorIdentity.key, -1, "helper")?.total)
            assertNull(repository.dao.rollup(calculatorIdentity.key, -1, "primary"))
            val raw = dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text("fixture ".repeat(500))
            val limitedTool = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
                override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("fixture_read", "Fixture", kotlinx.serialization.json.JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject) = dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(callId, raw, false)
            }
            val limited = dev.chungjungsoo.gptmobile.data.agent.tool.ConfiguredPluginTool(limitedTool, dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings(maxOutputCharacters = 1000))
            budget.bind(limited).execute("limited", kotlinx.serialization.json.JsonObject(emptyMap()))
            val recorded = repository.dao.invocations().single { it.subject == ToolIdentity.of("fixture_read").key }
            assertEquals("SUCCESS", recorded.outcome)
            assertEquals("TRUNCATED", recorded.delivery)
            repository.clear(identity.key)
            assertNull(repository.begin(identity, "profile", "run", "CLIENT", "call"))
            assertNull(repository.dao.rollup(identity.key, -1, "profile"))
        } finally {
            client.close()
        }
    }
}
