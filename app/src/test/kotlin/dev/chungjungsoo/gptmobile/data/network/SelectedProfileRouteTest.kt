package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedProfileRouteTest {
    @Test fun selectedModelAndEndpointStayFixedAcrossRequestCopies() {
        val selected = ProviderRequestConfig("http://127.0.0.1:8090/v1", null, lockedModel = "selected-model", lockedApiUrl = "http://127.0.0.1:8090/v1/")
        selected.requireSelectedRoute("selected-model")
        assertTrue(runCatching { selected.requireSelectedRoute("alternate-model") }.isFailure)
        assertTrue(runCatching { selected.copy(apiUrl = "http://10.0.2.2:8090/v1").requireSelectedRoute("selected-model") }.isFailure)
        assertTrue(RequestConstraints(lockSelectedRoute = true).withoutTools().forRole("github-review").lockSelectedRoute)
    }

    @Test fun automaticProviderAliasesCannotSubstituteAModelInGitHubMode() {
        for ((provider, models) in listOf("OPENROUTER" to listOf("openrouter/auto", "openrouter/free"), "FREE" to listOf("automatic", "auto", "kilo-auto/free"))) {
            for (model in models) {
                val config = ProviderRequestConfig("https://selected.example/v1", null, providerType = provider, lockedModel = model, lockedApiUrl = "https://selected.example/v1")
                val failure = runCatching { config.requireSelectedRoute(model) }.exceptionOrNull()
                assertTrue(failure?.message?.startsWith("GITHUB_PROFILE_LOCKED:") == true)
            }
        }
        // A local model may legitimately be named auto; no server routing alias is inferred.
        ProviderRequestConfig("http://local/v1", null, providerType = "LLAMA", lockedModel = "auto", lockedApiUrl = "http://local/v1").requireSelectedRoute("auto")
    }

    @Test fun ordinaryChatRetainsItsConfiguredAutomaticRouting() {
        ProviderRequestConfig("https://selected.example/v1", null, freeProvider = FreeAiProvider.LLM7).requireSelectedRoute("automatic")
        ProviderRequestConfig("https://selected.example/v1", null, providerType = "OPENROUTER").requireSelectedRoute("openrouter/auto")
    }
}
