package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProgressDeadlineSettings
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSessionDispatcherTest {
    @Test fun everyProviderKeepsItsSelectedRouteAndExactRequest() = runBlocking {
        val routes = mapOf(
            ClientType.OPENAI to "responses",
            ClientType.ANTHROPIC to "anthropic",
            ClientType.GOOGLE to "gemini",
            ClientType.GROQ to "compatible",
            ClientType.OPENROUTER to "compatible",
            ClientType.OLLAMA to "compatible",
            ClientType.CUSTOM to "compatible",
            ClientType.LLAMA to "compatible",
            ClientType.FREE to "compatible",
            ClientType.NVIDIA to "compatible",
            ClientType.LITERT_LM to "local"
        )
        assertEquals(ClientType.entries.toSet(), routes.keys)
        for ((type, route) in routes) {
            val seen = mutableListOf<Invocation>()
            val dispatcher = dispatcher(seen)
            val profile = PlatformV2(name = "Selected", uid = "selected", compatibleType = type, model = "unchanged-model", apiUrl = "http://selected.invalid", token = "fixture")
            val constraints = RequestConstraints(maxOutputTokens = 317, allowTools = false, allowReasoning = false, lockSelectedRoute = true, requestRole = "research-review", permissionRevision = "accepted-authority")
            val returned = dispatcher.openSession(turns, profile, constraints)
            assertSame(session, returned)
            val invocation = seen.single()
            assertEquals(route, invocation.route)
            assertSame(turns, invocation.turns)
            assertSame(profile, invocation.profile)
            assertSame(constraints, invocation.constraints)
        }
    }

    @Test fun remoteRequestsNeverPrepareLocalToolsOrReadLocalPreferences() = runBlocking {
        for (type in ClientType.entries.filterNot { it == ClientType.LITERT_LM }) {
            val seen = mutableListOf<Invocation>()
            dispatcher(seen).openSession(turns, PlatformV2(name = "Remote", compatibleType = type), RequestConstraints()) {
                error("Local preparation is not authorized for this request")
            }
            assertEquals(1, seen.size)
        }
    }

    @Test fun localRequestPreservesAuthorizedAndAdvertisedToolsSeparately() = runBlocking {
        val tools = listOf(mockk<AgentTool>())
        val advertised = listOf(mockk<AgentToolDefinition>())
        val options = LocalProviderSessionOptions(tools, "fallback", ProgressDeadlineSettings(firstProgressMillis = 1234), advertised, githubMode = true)
        var prepared = 0
        val seen = mutableListOf<Invocation>()
        dispatcher(seen).openSession(turns, PlatformV2(name = "Phone", compatibleType = ClientType.LITERT_LM), RequestConstraints()) {
            prepared++
            options
        }
        assertEquals(1, prepared)
        assertSame(options, seen.single().options)
        assertSame(tools, seen.single().options?.tools)
        assertSame(advertised, seen.single().options?.advertisedTools)
    }

    @Test fun providerFailureAndCancellationNeverSelectAnotherDestination() = runBlocking {
        for (failure in listOf(IllegalStateException("Unavailable selected provider"), CancellationException("Stopped"))) {
            val seen = mutableListOf<Invocation>()
            val dispatcher = dispatcher(seen, failure)
            val thrown = runCatching {
                dispatcher.openSession(turns, PlatformV2(name = "Selected", compatibleType = ClientType.OPENAI), RequestConstraints(lockSelectedRoute = true))
            }.exceptionOrNull()
            assertSame(failure, thrown)
            assertEquals(listOf("responses"), seen.map { it.route })
        }
    }

    @Test fun localPreparationFailureCannotLoadOrFallbackToAnyProvider() = runBlocking {
        val seen = mutableListOf<Invocation>()
        val failure = IllegalStateException("Tool authority changed")
        val thrown = runCatching {
            dispatcher(seen).openSession(turns, PlatformV2(name = "Phone", compatibleType = ClientType.LITERT_LM), RequestConstraints()) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertTrue(seen.isEmpty())
    }

    private data class Invocation(val route: String, val turns: List<ConversationTurn>, val profile: PlatformV2, val constraints: RequestConstraints, val options: LocalProviderSessionOptions? = null)

    private fun dispatcher(seen: MutableList<Invocation>, failure: Exception? = null): ProviderSessionDispatcher {
        fun remote(route: String) = RemoteProviderSessionFactory { turns, profile, constraints ->
            seen += Invocation(route, turns, profile, constraints)
            if (failure != null) throw failure
            session
        }
        return ProviderSessionDispatcher(remote("responses"), remote("compatible"), remote("anthropic"), remote("gemini")) { turns, profile, constraints, options ->
            seen += Invocation("local", turns, profile, constraints, options)
            if (failure != null) throw failure
            session
        }
    }

    private val turns = listOf(ConversationTurn(MessageV2(content = "Retain this exact task", platformType = null), null, true))
    private val session = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = emptyFlow()
    }
}
