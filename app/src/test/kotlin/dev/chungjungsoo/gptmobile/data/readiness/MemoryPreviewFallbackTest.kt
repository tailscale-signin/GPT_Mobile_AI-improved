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
