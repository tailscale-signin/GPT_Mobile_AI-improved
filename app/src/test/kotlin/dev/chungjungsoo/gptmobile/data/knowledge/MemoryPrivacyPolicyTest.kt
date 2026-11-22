package dev.chungjungsoo.gptmobile.data.knowledge

import dev.chungjungsoo.gptmobile.data.rag.KnowledgeEntity
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeFact
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeRelation
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPrivacyPolicyTest {
    private val fact = VaultFact("retained", KnowledgeFact(KnowledgeEntity("user", "User", "PERSON"), KnowledgeRelation("user", "PREFERS", "tea"), KnowledgeEntity("tea", "Tea", "THING")), sourceChatId = 11, sourceMessageId = 1)

    @Test fun retainedPrivateSourceBecomesIsolatedAndLocalOnlyWithoutChangingItsIdentity() {
        val protected = MemoryPrivacyPolicy.protect(fact, setOf(11))
        assertEquals(fact.id, protected.id)
        assertEquals("private:chat:11", protected.scope)
        assertTrue(protected.localOnly)
        assertEquals(fact.revision + 1, protected.revision)
        assertEquals(protected, MemoryPrivacyPolicy.protect(protected, setOf(11)))
    }

    @Test fun manualLabelsCannotMakeRetainedPrivateEvidenceOrdinary() {
        val ordinary = MemoryScope(privateChatIds = setOf(11))
        assertFalse(ordinary.acceptsSource(fact.copy(source = "manual")))
        val retained = fact.copy(sourceChatId = 22, evidenceSources = setOf("11:1"), source = "manual")
        assertFalse(ordinary.acceptsSource(retained))
        assertTrue(MemoryPrivacyPolicy.protect(retained, setOf(11)).localOnly)
    }

    @Test fun twoPrivateSourcesCannotBecomeVisibleInEitherConversation() {
        val protected = MemoryPrivacyPolicy.protect(fact.copy(evidenceSources = setOf("11:1", "12:2")), setOf(11, 12))
        assertEquals("private:unshared", protected.scope)
        assertTrue(protected.localOnly)
        for (chatId in listOf(11, 12)) {
            val scope = MemoryScope(key = MemoryPrivacyPolicy.scope(chatId), includePersonal = false, privateChatIds = setOf(11, 12))
            assertFalse(scope.accepts(protected.scope))
            assertFalse(scope.acceptsSource(protected))
        }
    }

    @Test fun privateCopiesCannotInheritAnotherConversationOrLoseTheirRestriction() {
        val protected = MemoryPrivacyPolicy.protect(fact, setOf(11))
        val owner = MemoryScope(key = "private:chat:11", includePersonal = false, privateChatIds = setOf(11, 12))
        val copy = MemoryScope(key = "private:chat:12", includePersonal = false, inheritedScopes = setOf("private:chat:11"), privateChatIds = setOf(11, 12))
        assertTrue(owner.acceptsSource(protected))
        assertFalse(copy.acceptsSource(protected))
        assertFalse(copy.accepts(protected.scope))
        assertFalse(owner.acceptsSource(protected.copy(localOnly = false)))
        assertTrue(MemoryPrivacyPolicy.protect(protected.copy(localOnly = false), emptySet()).localOnly)
    }

    @Test fun privateDocumentSourceIsExcludedFromAnOrdinaryProject() {
        val project = KnowledgeProject("shared", "Shared")
        val ordinary = MemoryScope(project = project, privateChatIds = setOf(11))
        val privateDocument = KnowledgeDocument("document", "Retained", "digest", projectId = project.id, chatId = 11)
        assertFalse(ordinary.accepts(privateDocument, 22))
        assertTrue(MemoryScope(key = "private:chat:11", includePersonal = false, privateChatIds = setOf(11)).accepts(privateDocument, 11))
        assertEquals(fact.copy(sourceChatId = 22), MemoryPrivacyPolicy.protect(fact.copy(sourceChatId = 22), setOf(11)))
    }
}
