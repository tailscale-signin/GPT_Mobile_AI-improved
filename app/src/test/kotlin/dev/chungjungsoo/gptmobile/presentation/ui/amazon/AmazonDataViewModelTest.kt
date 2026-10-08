package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonLocalHistory
import dev.chungjungsoo.gptmobile.data.amazon.AmazonWatchEntity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmazonDataViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profiles = MutableStateFlow(listOf(PlatformV2(uid = "owner", name = "Research"), PlatformV2(uid = "other", name = "Other")))
    private val watches = MutableStateFlow<List<AmazonWatchEntity>>(emptyList())
    private val repository = mockk<AmazonHistoryRepository>()
    private val provider = mockk<AmazonHtmlProvider>()
    private lateinit var viewModel: AmazonDataViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val settings = mockk<SettingRepository>()
        every { settings.observePlatformV2s() } returns profiles
        every { settings.observeFeatureSettings() } returns MutableStateFlow(AppFeatureSettings())
        every { repository.watches } returns watches
        coEvery { repository.history(any(), any<AmazonFreeMarket>(), any(), any()) } returns AmazonLocalHistory(emptyList(), 0, emptyList())
        viewModel = AmazonDataViewModel(repository, mockk<AmazonAccessPolicy>(), provider, settings)
    }

    @After fun close() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun historyReadPinsDefaultOwnerWhenTheProfileIsLaterDeleted() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()
        assertEquals("owner", viewModel.state.value.owner)
        viewModel.loadHistory(AmazonFreeMarket.CANADA, "B000000001")
        runCurrent()
        profiles.value = profiles.value.filterNot { it.uid == "owner" }
        runCurrent()
        assertEquals("owner", viewModel.state.value.owner)
        assertEquals(null, viewModel.state.value.selectedProfile)
        assertEquals("B000000001", viewModel.state.value.historyAsin)
        coVerify(exactly = 0) { provider.search(any(), any()) }
        coVerify(exactly = 0) { provider.products(any(), any()) }
    }

    @Test fun cardForDeletedOwnerShowsOrphanedTargetsWithoutTransferringToAnotherProfile() = runTest(dispatcher) {
        profiles.value = profiles.value.filterNot { it.uid == "owner" }
        watches.value = listOf(AmazonWatchEntity("watch", "owner", "amazon.ca", "B000000001", "80", "CAD", createdAt = 1))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()
        viewModel.openListing("owner", AmazonFreeMarket.CANADA, "B000000001")
        runCurrent()
        val state = viewModel.state.value
        assertEquals("owner", state.owner)
        assertEquals("watch", state.watches.single().id)
        assertFalse(state.canCheck)
        assertFalse(state.canSave)
        coVerify(exactly = 0) { provider.products(any(), any()) }
        coVerify(exactly = 0) { repository.saveWatch(any(), any(), any(), any(), any(), any(), any()) }
    }
}
