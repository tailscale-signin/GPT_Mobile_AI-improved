package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmazonProductHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val access = mockk<AmazonAccessPolicy>()
    private val provider = mockk<AmazonPublicHistoryProvider>()
    private lateinit var model: AmazonProductHistoryViewModel
    private fun product(id: Int) = Json.parseToJsonElement("""{"asin":"B${id.toString().padStart(9, '0')}","marketplace":"amazon.ca"}""") as JsonObject

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { access.mediaAllowed("owner") } returns true
        coEvery { provider.fetch(any(), any(), any()) } returns AmazonPublicHistory("Keepa", "source", "chart", 1)
        model = AmazonProductHistoryViewModel(provider, access)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun allThirtyResultsAreWarmedWithoutOpeningOrComposingEachCard() = runTest(dispatcher) {
        model.preload("owner", (1..35).map(::product))
        runCurrent()
        coVerify(exactly = 30) { provider.fetch("amazon.ca", any(), any()) }
        coVerify(exactly = 1) { provider.fetch("amazon.ca", "B000000030", any()) }
        coVerify(exactly = 0) { provider.fetch("amazon.ca", "B000000031", any()) }
    }

    @Test fun duplicateOffersAndRepeatedResultsShareAnActivePreload() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        coEvery { provider.fetch(any(), any(), any()) } coAnswers {
            release.await()
            AmazonPublicHistory("Keepa", "source", "chart", 1)
        }
        model.preload("owner", listOf(product(1), product(1)))
        model.preload("owner", listOf(product(1)))
        runCurrent()
        coVerify(exactly = 1) { provider.fetch("amazon.ca", "B000000001", any()) }
        release.complete(Unit)
        runCurrent()
    }

    @Test fun missingOwnersOrRevokedAmazonAccessDoNotPreload() = runTest(dispatcher) {
        model.preload(null, listOf(product(1)))
        coEvery { access.mediaAllowed("owner") } returns false
        model.preload("owner", listOf(product(1)))
        runCurrent()
        coVerify(exactly = 0) { provider.fetch(any(), any(), any()) }
    }
}
