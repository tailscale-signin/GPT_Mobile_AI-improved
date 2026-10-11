package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderConnectionDraftViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun recreatingEditorDoesNotReloadOverAnUnsavedCredential() = runTest {
        val draft = ProviderConnectionDraftViewModel()
        draft.load { listOf("saved") }
        draft.keys = listOf("edited")
        draft.load { error("A recreated editor must reuse the in-memory draft") }
        assertEquals(listOf("edited"), draft.keys)
        assertTrue(draft.dirty)
        draft.discard()
        assertTrue(draft.keys.isEmpty())
        assertFalse(draft.loaded)
    }

    @Test
    fun failedSaveRetainsDraftAndDoesNotCloseEditor() = runTest {
        val draft = ProviderConnectionDraftViewModel()
        draft.load { listOf("saved") }
        draft.keys = listOf("edited")
        var closed = false
        draft.save(write = { error("Storage unavailable") }, onSaved = { closed = true })
        assertFalse(closed)
        assertFalse(draft.saving)
        assertEquals(listOf("edited"), draft.keys)
        assertNotNull(draft.status)
        assertTrue(draft.dirty)
        var written = emptyList<String>()
        draft.save(write = { written = it }, onSaved = { closed = true })
        assertEquals(listOf("edited"), written)
        assertTrue(closed)
        assertFalse(draft.dirty)
    }
}
