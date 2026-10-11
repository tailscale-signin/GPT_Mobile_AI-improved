package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.health.HealthConnectTools
import dev.chungjungsoo.gptmobile.data.health.HealthDataReader
import dev.chungjungsoo.gptmobile.data.health.HealthQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectToolTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-11T19:00:00Z"), ZoneId.of("America/Toronto"))

    @Test fun disabledPluginNeverReadsEvenWithAiConsent() = runTest {
        val reader = FakeReader()
        val result = tool(reader, access = { false }).execute("c", buildJsonObject {})
        assertEquals("plugin_disabled", result.errorCode)
        assertEquals(0, reader.calls)
    }

    @Test fun measurementsRequireSeparateAiConsent() = runTest {
        val reader = FakeReader()
        val result = tool(reader, consent = { false }).execute("c", buildJsonObject {})
        assertEquals("ai_consent_required", result.errorCode)
        assertEquals(0, reader.calls)
    }

    @Test fun statusCanExplainSetupWithoutReleasingMeasurements() = runTest {
        val reader = FakeReader()
        assertFalse(tool(reader, operation = "status", consent = { false }).execute("s", buildJsonObject {}).isError)
        assertEquals("status", reader.operation)
        assertEquals("invalid_arguments", tool(reader, operation = "status").execute("s2", buildJsonObject { put("days", 1) }).errorCode)
        assertEquals(1, reader.calls)
    }

    @Test fun malformedQueriesFailBeforeProviderRead() = runTest {
        val reader = FakeReader()
        val result = tool(reader).execute("c", buildJsonObject { put("days", 500) })
        assertEquals("invalid_arguments", result.errorCode)
        assertEquals(0, reader.calls)
    }

    @Test fun revocationDuringAReadDiscardsLoadedResults() = runTest {
        var consent = true
        val reader = FakeReader { consent = false }
        val result = tool(reader, consent = { consent }).execute("c", buildJsonObject {})
        assertEquals("consent_revoked", result.errorCode)
        assertFalse(result.content.toString().contains("private-measurement"))
    }

    @Test fun disablingProfileDuringAReadDiscardsLoadedResults() = runTest {
        var access = true
        val reader = FakeReader { access = false }
        val result = tool(reader, access = { access }).execute("c", buildJsonObject {})
        assertEquals("consent_revoked", result.errorCode)
    }

    @Test fun toolIsPhoneOwnedAndHasRedactedTraceButUsefulLivePayload() = runTest {
        val tool = tool(FakeReader())
        assertEquals(AgentToolExecutionOwner.CLIENT, tool.executionOwner)
        val result = tool.execute("c", buildJsonObject { put("days", 7) })
        assertFalse(result.isError)
        assertTrue(result.content.toString().contains("private-measurement"))
        assertEquals(ToolResultContent.Text(HealthConnectTools.REDACTED), result.traceContent)
        assertNull(result.retainedContent)
        assertFalse(result.sharedResult)
    }

    @Test fun providerErrorTextIsNeverDisclosed() = runTest {
        val reader = FakeReader { throw IllegalStateException("private health value 987654") }
        val result = tool(reader).execute("c", buildJsonObject {})
        assertEquals("health_connect_unavailable", result.errorCode)
        assertFalse(result.content.toString().contains("987654"))
    }

    @Test fun cancellationAndParentTimeoutPropagateRatherThanFabricatingAResult() = runTest {
        val reader = FakeReader { throw CancellationException("cancelled") }
        assertTrue(runCatching { tool(reader).execute("c", buildJsonObject {}) }.exceptionOrNull() is CancellationException)
        val delayed = FakeReader { delay(1000) }
        assertNull(withTimeoutOrNull(10) { tool(delayed).execute("timeout", buildJsonObject {}) })
    }

    private fun tool(reader: HealthDataReader, operation: String = "summary", access: suspend () -> Boolean = { true }, consent: suspend () -> Boolean = { true }) = HealthConnectTool(operation, reader, access, consent, clock)

    private class FakeReader(private val onRead: suspend () -> Unit = {}) : HealthDataReader {
        var calls = 0
        var operation: String? = null
        override fun availability() = "available"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun revokePermissions() = Unit
        override suspend fun read(operation: String, query: HealthQuery): JsonObject {
            calls++
            this.operation = operation
            onRead()
            return buildJsonObject { put("status", "available"); if (operation != "status") put("value", "private-measurement") }
        }
    }
}
