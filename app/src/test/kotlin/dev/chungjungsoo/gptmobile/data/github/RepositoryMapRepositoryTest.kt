package dev.chungjungsoo.gptmobile.data.github

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryMapRepositoryTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud")
    private val phone = mockk<PhoneRepositoryGraphRepository>()
    private val gateway = mockk<GatewayGraphRepository>()
    private val maps = RepositoryMapRepository(phone, gateway)
    private val status = buildJsonObject { put("action", "status") }

    @Test fun defaultMapUsesPhoneWithoutLookingForAnyGateway() = runTest {
        coEvery { phone.executeForMap(context, status, false) } returns response("phone")
        val result = maps.executeForMap(context, status)
        assertEquals(JsonPrimitive("phone"), result.result["map_source"])
        assertEquals("phone", result.gatewayProfileUid)
        coVerify(exactly = 1) { phone.executeForMap(context, status, false) }
        coVerify(exactly = 0) { gateway.executeForMap(any(), any(), any(), any(), any()) }
    }

    @Test fun explicitlySelectedGatewayReceivesOnlyItsOwnArgumentsAndHostBinding() = runTest {
        coEvery { gateway.executeForMap(context, status, "chosen-host", false, false) } returns response("chosen-host")
        val result = maps.executeForMap(context, selected("gateway"), "chosen-host", false)
        assertEquals(JsonPrimitive("gateway"), result.result["map_source"])
        assertEquals("chosen-host", result.gatewayProfileUid)
        coVerify(exactly = 1) { gateway.executeForMap(context, status, "chosen-host", false, false) }
        coVerify(exactly = 0) { phone.executeForMap(any(), any(), any()) }
    }

    @Test fun failedPhoneReadNeverUsesAConfiguredGatewayAsAnImplicitNewDestination() = runTest {
        val failure = IOException("GitHub unreachable")
        coEvery { phone.executeForMap(context, status, false) } throws failure
        assertSame(failure, runCatching { maps.executeForMap(context, status, "configured-host") }.exceptionOrNull())
        coVerify(exactly = 0) { gateway.executeForMap(any(), any(), any(), any(), any()) }
    }

    @Test fun gatewayPolicyFailureDoesNotBypassItsDecisionThroughPhone() = runTest {
        val failure = GatewayGraphAdmissionException("PERMISSION_DENIED", "Sharing is disabled")
        coEvery { gateway.executeForMap(context, status, null, true, false) } throws failure
        assertSame(failure, runCatching { maps.executeForMap(context, selected("gateway")) }.exceptionOrNull())
        coVerify(exactly = 0) { phone.executeForMap(any(), any(), any()) }
    }

    @Test fun unknownOrNonStringSourcesCannotSelectADestination() = runTest {
        for (source in listOf(JsonPrimitive("auto"), JsonPrimitive(""), JsonPrimitive(true), JsonObject(emptyMap()))) {
            val failure = runCatching { maps.executeForMap(context, JsonObject(status + ("map_source" to source))) }.exceptionOrNull()
            assertTrue(failure is GatewayGraphAdmissionException)
            assertEquals("INVALID_ARGUMENTS", (failure as GatewayGraphAdmissionException).code)
        }
        coVerify(exactly = 0) { phone.executeForMap(any(), any(), any()) }
        coVerify(exactly = 0) { gateway.executeForMap(any(), any(), any(), any(), any()) }
    }

    @Test fun modelPhoneReadsUseModelAdmissionAndDoNotInventRemoteIndexCheckpoints() = runTest {
        coEvery { phone.executeForMap(context, status, true) } returns response("phone")
        val result = maps.execute(context, status)
        assertEquals(JsonPrimitive("phone"), result["map_source"])
        assertFalse("index_checkpoint" in result)
        coVerify(exactly = 1) { phone.executeForMap(context, status, true) }
        coVerify(exactly = 0) { gateway.execute(any(), any()) }
    }

    @Test fun modelGatewayReadsRetainTheirTypedUnresolvedCheckpoint() = runTest {
        val checkpoint = buildJsonObject { put("index_outcome_unknown", true) }
        coEvery { gateway.execute(context, status) } returns buildJsonObject { put("index_checkpoint", checkpoint) }
        val result = maps.execute(context, selected("gateway"))
        assertEquals(checkpoint, result["index_checkpoint"])
        assertEquals(JsonPrimitive("gateway"), result["map_source"])
        coVerify(exactly = 0) { phone.executeForMap(any(), any(), any()) }
    }

    private fun selected(source: String) = JsonObject(status + ("map_source" to JsonPrimitive(source)))
    private fun response(source: String) = GatewayGraphResponse(buildJsonObject { put("status", "INDEXED") }, "status", source, source)
}
