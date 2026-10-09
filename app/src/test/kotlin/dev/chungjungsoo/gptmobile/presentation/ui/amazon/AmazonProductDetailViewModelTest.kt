package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductMedia
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductMediaRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicHistoryProvider
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
    private val media = mockk<AmazonProductMediaRepository>()
    private val product = Json.parseToJsonElement("""{"asin":"B000000001","marketplace":"amazon.ca","title":"Headphones","price":"CAD 50","imageUrl":"https://m.media-amazon.com/images/I/product.jpg"}""") as JsonObject
    private val history = AmazonPublicHistory("Keepa", "https://keepa.com/#!product/6-B000000001", "https://graph.keepa.com/chart", 1, byteArrayOf(1))
    private lateinit var model: AmazonProductDetailViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { access.mediaChanges("owner") } returns allowed
        coEvery { access.mediaAllowed("owner") } answers { allowed.value }
        coEvery { histories.fetch("amazon.ca", "B000000001", any()) } returns history
        coEvery { media.photo("owner", any(), product) } returns AmazonProductMedia(product, byteArrayOf(2))
        coEvery { media.details("owner", any(), product, any()) } returns product
        model = AmazonProductDetailViewModel(histories, media, access)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun aPreloadedGraphAndProductImageAppearWhileDetailsAreStillLoading() = runTest(dispatcher) {
        val details = CompletableDeferred<JsonObject>()
        coEvery { media.details("owner", any(), product, any()) } coAnswers { details.await() }
        model.open("owner", product)
        runCurrent()
        assertEquals(history, model.state.value.publicHistory)
        assertNotNull(model.state.value.image)
        assertFalse(model.state.value.historyLoading)
        assertTrue(model.state.value.loading)
        details.complete(product)
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
        coEvery { media.details(any(), any(), any(), any()) } throws IllegalStateException("Provider unavailable")
        model.open("owner", product)
        runCurrent()
        assertEquals(history, model.state.value.publicHistory)
        val retained = requireNotNull(model.state.value.product)
        product.forEach { (field, value) -> assertEquals(field, value, retained[field]) }
        assertEquals(dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts.productImageUrls(product), dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts.productImageUrls(retained))
        assertFalse(model.state.value.loading)
    }

    @Test fun disabledProfilesDoNotFetchChartsImagesOrDetails() = runTest(dispatcher) {
        allowed.value = false
        model.open("owner", product)
        runCurrent()
        coVerify(exactly = 0) { histories.fetch(any(), any(), any()) }
        coVerify(exactly = 0) { media.photo(any(), any(), any()) }
        coVerify(exactly = 0) { media.details(any(), any(), any(), any()) }
    }

    @Test fun closingThePopupCancelsWorkAndLeavesAnEmptyState() = runTest(dispatcher) {
        val details = CompletableDeferred<JsonObject>()
        coEvery { media.details("owner", any(), product, any()) } coAnswers { details.await() }
        model.open("owner", product)
        runCurrent()
        model.close()
        details.complete(product)
        runCurrent()
        assertEquals(AmazonProductDetailState(), model.state.value)
    }
}
