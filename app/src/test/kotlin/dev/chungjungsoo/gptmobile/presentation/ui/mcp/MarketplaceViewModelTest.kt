package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplacePackageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketplaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<MarketplacePackageStore>()
    private val viewModels = ViewModelStore()
    private val entry = GitHubMarketplaceCatalog.packages.first()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { store.downloadedIds() } returns emptySet()
        coEvery { store.download(any()) } returns Unit
        coEvery { store.remove(any()) } returns Unit
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun model() = MarketplaceViewModel(store).also { viewModels.put("marketplace", it) }

    @Test
    fun startupScanCannotOverwriteNewDownload() = runTest(dispatcher) {
        val scan = CompletableDeferred<Set<String>>()
        coEvery { store.downloadedIds() } coAnswers { scan.await() }
        val model = model()
        model.download(entry)
        runCurrent()
        coVerify(exactly = 0) { store.download(any()) }
        scan.complete(emptySet())
        runCurrent()
        assertEquals(setOf(entry.id), model.uiState.value.downloadedIds)
        assertTrue(model.uiState.value.downloadingIds.isEmpty())
    }

    @Test
    fun rapidCancelBeforeStartReleasesDownloadForRetry() = runTest(dispatcher) {
        val model = model()
        model.download(entry)
        model.cancelDownload(entry.id)
        runCurrent()
        assertTrue(model.uiState.value.downloadingIds.isEmpty())
        coVerify(exactly = 0) { store.download(any()) }
        model.download(entry)
        runCurrent()
        assertEquals(setOf(entry.id), model.uiState.value.downloadedIds)
    }

    @Test
    fun repeatedDownloadAndRemoveDoNotRaceActiveDownload() = runTest(dispatcher) {
        val completion = CompletableDeferred<Unit>()
        coEvery { store.download(entry) } coAnswers { completion.await() }
        val model = model()
        model.download(entry)
        runCurrent()
        model.download(entry)
        model.remove(entry)
        runCurrent()
        coVerify(exactly = 1) { store.download(entry) }
        coVerify(exactly = 0) { store.remove(entry) }
        completion.complete(Unit)
        runCurrent()
        assertEquals(setOf(entry.id), model.uiState.value.downloadedIds)
    }

    @Test
    fun cancelledActiveDownloadClearsBusyStateAndCanRetry() = runTest(dispatcher) {
        coEvery { store.download(entry) } coAnswers { CompletableDeferred<Unit>().await() }
        val model = model()
        model.download(entry)
        runCurrent()
        model.cancelDownload(entry.id)
        runCurrent()
        assertTrue(model.uiState.value.downloadingIds.isEmpty())
        assertFalse(entry.id in model.uiState.value.downloadedIds)
        coEvery { store.download(entry) } returns Unit
        model.download(entry)
        runCurrent()
        assertEquals(setOf(entry.id), model.uiState.value.downloadedIds)
        assertFalse(entry.id in model.uiState.value.errors)
    }

    @Test
    fun failedRemovalPreservesDownloadedStateAndCanRetry() = runTest(dispatcher) {
        coEvery { store.downloadedIds() } returns setOf(entry.id)
        coEvery { store.remove(entry) } throws IOException("disk failure")
        val model = model()
        model.remove(entry)
        runCurrent()
        assertEquals(setOf(entry.id), model.uiState.value.downloadedIds)
        assertTrue(model.uiState.value.removingIds.isEmpty())
        assertTrue(entry.id in model.uiState.value.errors)
        coEvery { store.remove(entry) } returns Unit
        model.remove(entry)
        runCurrent()
        assertTrue(model.uiState.value.downloadedIds.isEmpty())
        assertTrue(model.uiState.value.errors.isEmpty())
    }

    @Test
    fun failedDownloadDoesNotExposeExceptionOrPreventRetry() = runTest(dispatcher) {
        coEvery { store.download(entry) } throws IOException("secret-example")
        val model = model()
        model.download(entry)
        runCurrent()
        assertFalse(entry.id in model.uiState.value.downloadedIds)
        assertFalse(model.uiState.value.errors.getValue(entry.id).contains("secret-example"))
        coEvery { store.download(entry) } returns Unit
        model.download(entry)
        runCurrent()
        assertEquals(setOf(entry.id), model.uiState.value.downloadedIds)
    }
}
