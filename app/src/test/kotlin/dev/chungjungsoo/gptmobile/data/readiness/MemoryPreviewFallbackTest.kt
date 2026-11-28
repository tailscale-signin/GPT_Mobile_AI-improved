package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope
import dev.chungjungsoo.gptmobile.data.rag.FactRecall
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class MemoryPreviewFallbackTest {
    @Test fun listeningSearchReusesExactWordsAndRejectsChangedHistoryScopePermissionsAndOwners() = runTest {
        val vault = mockk<FactVaultRepository>(relaxed = true)
        val snapshot = MutableStateFlow(FactVaultSnapshot(enabled = true))
        var boundary = MemoryScope()
        val words = "What did I say about the costume?"
        val remembered = FactRecall(includesHistory = true)
        coEvery { vault.state } returns snapshot
        coEvery { vault.scopeForChat(1) } answers { boundary }
        coEvery { vault.recallReadOnly(words, 1, 0, true, false, "previous", any()) } returns remembered
        val coordinator = MemoryPreviewCoordinator(vault, backgroundScope)
        val owner = coordinator.openVoice(1, "profile")
        coordinator.previewVoice(owner, words, true, false, "previous")
        coordinator.previewVoice(owner, words, true, false, "previous")
        io.mockk.coVerify(exactly = 2) { vault.recallReadOnly(words, 1, 0, true, false, "previous", any()) }
        suspend fun recall(text: String = words, history: String = "previous") = coordinator.voiceRecall(1, "profile", text, 17, true, false, history)
        org.junit.Assert.assertEquals(remembered, recall())
        org.junit.Assert.assertNull(recall("What did I say about the custom?"))
        org.junit.Assert.assertNull(recall(history = "another turn"))
        boundary = MemoryScope(key = "project:other")
        org.junit.Assert.assertNull(recall())
        boundary = MemoryScope()
        snapshot.value = snapshot.value.copy(enabled = false)
        org.junit.Assert.assertNull(recall())
        snapshot.value = snapshot.value.copy(enabled = true)
        val replacement = coordinator.openVoice(1, "profile")
        coordinator.closeVoice(owner)
        org.junit.Assert.assertNull(recall())
        coordinator.previewVoice(replacement, words, true, false, "previous")
        coordinator.closeVoice(replacement)
        org.junit.Assert.assertNull(recall())
    }

    @Test fun slowSemanticLookupReturnsCompletedLexicalFallbackAtDeadline() = runTest {
        val vault = mockk<FactVaultRepository>()
        val lexical = FactRecall()
        coEvery { vault.state } returns MutableStateFlow(FactVaultSnapshot(enabled = true))
        coEvery { vault.scopeForChat(1) } returns MemoryScope()
        coEvery { vault.recallReadOnly("toolbar", 1, 7, true, false, "", false) } returns lexical
        coEvery { vault.recallReadOnly("toolbar", 1, 7, true, false, "", true) } coAnswers { awaitCancellation() }
        val coordinator = MemoryPreviewCoordinator(vault, backgroundScope)
        assertSame(lexical, coordinator.submitted(1, "local", "toolbar", 7, true, false, "", 50))
    }
}
