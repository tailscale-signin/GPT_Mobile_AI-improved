package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductImageProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmazonProductDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val allowed = MutableStateFlow(true)
    private val access = mockk<AmazonAccessPolicy>()
    private val histories = mockk<AmazonPublicHistoryProvider>()
    private val images = mockk<AmazonProductImageProvider>()
    private val tools = mockk<AgentToolResolver>()
    private val product = Json.parseToJsonElement("""{"asin":"B000000001","marketplace":"amazon.ca","title":"Headphones","price":"CAD 50","imageUrl":"https://m.media-amazon.com/images/I/product.jpg"}""") as JsonObject
    private val history = AmazonPublicHistory("Keepa", "https://keepa.com/#!product/6-B000000001", "https://graph.keepa.com/chart", 1, byteArrayOf(1))
    private lateinit var model: AmazonProductDetailViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { access.mediaChanges("owner") } returns allowed
        coEvery { access.mediaAllowed("owner") } answers { allowed.value }
        coEvery { access.allowed("owner", true) } returns false
        coEvery { histories.fetch("amazon.ca", "B000000001", any()) } returns history
        coEvery { images.fetch(any(), any()) } returns byteArrayOf(2)
        coEvery { tools.amazonProductDetails("owner", "amazon.ca", "B000000001") } returns null
        model = AmazonProductDetailViewModel(histories, images, mockk<AmazonHtmlProvider>(), access, mockk<SettingRepository>(), tools)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun aPreloadedGraphAndProductImageAppearWhileDetailsAreStillLoading() = runTest(dispatcher) {
        val details = CompletableDeferred<JsonObject?>()
        coEvery { tools.amazonProductDetails("owner", "amazon.ca", "B000000001") } coAnswers { details.await() }
        model.open("owner", product)
        runCurrent()
        assertEquals(history, model.state.value.publicHistory)
        assertNotNull(model.state.value.image)
        assertFalse(model.state.value.historyLoading)
        assertTrue(model.state.value.loading)
        details.complete(null)
        runCurrent()
        assertFalse(model.state.value.loading)
    }

    @Test fun revocationCancelsLoadingAndRemovesProductMediaWithoutDiscardingTheListing() = runTest(dispatcher) {
        model.open("owner", product)
        runCurrent()
        assertNotNull(model.state.value.publicHistory)
        allowed.value = false
        runCurrent()
        assertNull(model.state.value.publicHistory)
        assertNull(model.state.value.image)
        assertEquals(product, model.state.value.product)
        assertFalse(model.state.value.loading)
        assertNotNull(model.state.value.notice)
    }

    @Test fun failedAdditionalInformationDoesNotRemoveACachedGraphOrListing() = runTest(dispatcher) {
        coEvery { tools.amazonProductDetails(any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        model.open("owner", product)
        runCurrent()
        assertEquals(history, model.state.value.publicHistory)
        assertEquals(product, model.state.value.product)
        assertFalse(model.state.value.loading)
    }

    @Test fun disabledProfilesDoNotFetchChartsImagesOrDetails() = runTest(dispatcher) {
        allowed.value = false
        model.open("owner", product)
        runCurrent()
        coVerify(exactly = 0) { histories.fetch(any(), any(), any()) }
        coVerify(exactly = 0) { images.fetch(any(), any()) }
        coVerify(exactly = 0) { tools.amazonProductDetails(any(), any(), any()) }
    }

    @Test fun closingThePopupCancelsWorkAndLeavesAnEmptyState() = runTest(dispatcher) {
        val details = CompletableDeferred<JsonObject?>()
        coEvery { tools.amazonProductDetails("owner", "amazon.ca", "B000000001") } coAnswers { details.await() }
        model.open("owner", product)
        runCurrent()
        model.close()
        details.complete(null)
        runCurrent()
        assertEquals(AmazonProductDetailState(), model.state.value)
    }
}
