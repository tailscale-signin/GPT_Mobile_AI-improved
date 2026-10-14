package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestAuthorizationGuardTest {
    @Test fun deniedEvidenceReturnsFailureWithoutConstructingNativeWork() = runTest {
        var constructed = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                constructed++
                return flow { emit(ProviderEvent.TextDelta("must not run")) }
            }
        }
        val events = provider.withRequestAuthorization("permission-1") { throw SavedEvidenceAccessDenied() }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(0, constructed)
        assertTrue((events.single() as ProviderEvent.Failed).message.startsWith("SOURCE_PERMISSION_REVOKED"))
    }

    @Test fun revocationAfterRoundPreparationReturnsFailureAtPhysicalBoundary() = runTest {
        var calls = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
                emit(ProviderEvent.TextDelta("must not run"))
            }
        }
        val events = provider.withRequestAuthorization("permission-2") {
            if (++calls == 2) throw SavedEvidenceAccessDenied()
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, calls)
        assertTrue(events.single() is ProviderEvent.Failed)
    }

    @Test fun cancellationIsNeverConvertedToPermissionFailure() = runTest {
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow { throw CancellationException("stop") }
        }
        val failure = runCatching { provider.withRequestAuthorization("permission") {}.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
