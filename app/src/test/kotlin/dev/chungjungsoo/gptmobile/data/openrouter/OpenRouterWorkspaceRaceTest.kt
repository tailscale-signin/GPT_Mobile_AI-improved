package dev.chungjungsoo.gptmobile.data.openrouter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptStore
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterTab
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterModelRepository
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepositoryImpl
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.presentation.viewmodel.OpenRouterSettingsViewModel
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class OpenRouterWorkspaceRaceTest {
    @Test fun replacementClearsScopedPanelsAndIgnoresOldEndpointFailures() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture { client ->
                client.vm.editKey("active")
                client.vm.validate()
                await { client.vm.state.value.unlocked }
                client.payload = { path ->
                    if (path.endsWith("/providers")) """{"data":[{"slug":"old-provider"}]}""" else Fixture.KEY_RESPONSE
                }
                client.vm.loadProviders()
                await { client.vm.state.value.providers.isNotEmpty() }
                client.response = { key ->
                    if (key == "active") {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        500
                    } else {
                        200
                    }
                }
                client.vm.compareProviders("fixture/model")
                withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
                assertTrue(client.vm.state.value.endpointLoading)
                client.vm.editKey("replacement")
                client.vm.validate()
                await { client.credential == "replacement" && !client.vm.state.value.validating }
                assertTrue(client.vm.state.value.providers.isEmpty())
                assertFalse(client.vm.state.value.endpointLoading)
                assertNull(client.vm.state.value.endpoints)
                release.countDown()
                delay(30)
                assertFalse(client.vm.state.value.catalogError)
                assertTrue(client.vm.state.value.unlocked)
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun lateValidationCannotReplaceANewerCredential() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture { client ->
            client.response = { key ->
                if (key == "A") {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                200
            }
            client.vm.editKey("A")
            client.vm.validate()
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            client.vm.editKey("B")
            client.vm.validate()
            await { client.vm.state.value.unlocked }
            release.countDown()
            delay(30)
            assertEquals("B", client.credential)
            assertTrue(client.vm.state.value.unlocked)
        }
    }

    @Test fun accountRefreshDoesNotCommitOrDiscardAReplacementDraft() = runBlocking {
        fixture { client ->
            client.vm.editKey("active")
            client.vm.validate()
            await { client.vm.state.value.unlocked }
            client.vm.editKey("replacement")
            client.vm.validate(false)
            await { !client.vm.state.value.validating }
            assertEquals("active", client.credential)
            assertEquals("replacement", client.vm.draftKey.value)
            assertEquals("active", client.lastRequested)
        }
    }

    @Test fun invalidReplacementPreservesActiveConnectionAndZeroBudgetUnlocks() = runBlocking {
        fixture { client ->
            client.vm.editKey("active")
            client.vm.validate()
            await { client.vm.state.value.unlocked }
            client.response = { if (it == "invalid") 401 else 200 }
            client.vm.editKey("invalid")
            client.vm.validate()
            await { !client.vm.state.value.validating }
            assertTrue(client.vm.state.value.unlocked)
            assertEquals("active", client.credential)
            assertTrue(client.vm.state.value.snapshot!!.exhausted)
            client.vm.selectTab(OpenRouterTab.ACCOUNT)
            assertEquals(OpenRouterTab.ACCOUNT, client.vm.state.value.tab)
        }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val dispatcher = newSingleThreadContext("router-workspace-test")
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            withContext(dispatcher) {
                val value = Fixture()
                store.put("workspace", value.vm)
                await { !value.vm.state.value.loading && value.vm.state.value.connection != null }
                assertFalse(value.vm.state.value.unlocked)
                value.vm.selectTab(OpenRouterTab.ACCOUNT)
                assertEquals(OpenRouterTab.SETUP, value.vm.state.value.tab)
                block(value)
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
            dispatcher.close()
        }
    }
    private suspend fun await(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(5) }
    private class Fixture {
        var credential = ""
        var lastRequested = ""
        var response: (String) -> Int = { 200 }
        var payload: (String) -> String = { KEY_RESPONSE }
        var connection = ProviderConnection("fixture", "Fixture", ClientType.OPENROUTER, "https://openrouter.ai/api/v1")
        private val settings = Proxy.newProxyInstance(SettingRepository::class.java.classLoader, arrayOf(SettingRepository::class.java)) { _, method, args ->
            when (method.name) {
                "fetchProviderConnections" -> listOf(connection)
                "getProviderConnection" -> connection
                "fetchPlatformV2s" -> emptyList<dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2>()
                "getProviderCredentials" -> credential
                "replaceProviderCredential" -> {
                    check(args[2] == credential)
                    credential = args[4] as? String ?: credential
                    connection = connection.copy(apiUrl = args[3] as String)
                    connection
                }
                else -> error(method.name)
            }
        } as SettingRepository
        private val preferences = InMemoryRouterPreferences()
        private val vault = object : SecretVault {
            override suspend fun put(secretRef: String, secret: ByteArray) = Unit
            override suspend fun read(secretRef: String): ByteArray? = null
            override suspend fun delete(secretRef: String) = Unit
        }
        private val client = OpenRouterAccountClient { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    val key = getRequestProperty("Authorization").removePrefix("Bearer ")
                    lastRequested = key
                    return response(key)
                }
                override fun getInputStream() = ByteArrayInputStream(payload(url.path).toByteArray())
            }
        }
        val vm = OpenRouterSettingsViewModel(OpenRouterSettingsRepositoryImpl(preferences, settings, vault), settings, client, OpenRouterModelRepository(), OpenRouterReceiptStore(preferences), SavedStateHandle(mapOf("connectionUid" to "fixture")))
        companion object {
            const val KEY_RESPONSE = """{"data":{"label":"fixture","usage":0,"is_free_tier":false,"is_management_key":false,"limit_remaining":0}}"""
        }
    }
}
