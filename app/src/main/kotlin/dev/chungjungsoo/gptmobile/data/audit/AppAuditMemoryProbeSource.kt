package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.memory.v2.FactValidity
import dev.chungjungsoo.gptmobile.data.memory.v2.memoryHalfLifeWeight
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSettings
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeEntity
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeFact
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeRelation
import dev.chungjungsoo.gptmobile.data.rag.MemoryEligibility
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import javax.inject.Inject

/** Disposable records and a fresh graph; the user's vault and native index are never opened. */
internal class AppAuditMemoryProbeSource @Inject constructor() :
    AppAuditFixtureSource(
        "memory_contract",
        listOf(
            AppAuditFixture("memory_eligibility_v1", "Memory recall privacy, review and conversation gates", listOf("memory_and_rag", "auth_and_security")) {
                val subject = KnowledgeEntity("fixture-user", "Fixture", "PERSON")
                val target = KnowledgeEntity("fixture-topic", "Fixture topic", "CONCEPT")
                val fact = VaultFact("fixture-record", KnowledgeFact(subject, KnowledgeRelation(subject.id, "PREFERS", target.id), target), sourceChatId = 7, sourceMessageId = 11)
                fun accepts(record: VaultFact = fact, settings: FactVaultSettings = FactVaultSettings(), local: Boolean = false, includeLocal: Boolean = false, message: Int = 0) =
                    MemoryEligibility.accepts(record, settings, 8, message, local, includeLocal, 1_000)
                verify(accepts())
                verify(!accepts(settings = FactVaultSettings(recallEnabled = false)))
                verify(!accepts(settings = FactVaultSettings(allowCloudRecall = false)))
                verify(accepts(settings = FactVaultSettings(allowCloudRecall = false), local = true))
                verify(!accepts(fact.copy(localOnly = true)))
                verify(!accepts(fact.copy(localOnly = true), local = true))
                verify(accepts(fact.copy(localOnly = true), local = true, includeLocal = true))
                verify(!accepts(fact.copy(awaitingReview = true)))
                verify(!accepts(fact.copy(enabled = false)))
                verify(!accepts(fact.copy(supersededBy = "fixture-new")))
                verify(!accepts(settings = FactVaultSettings(sameChatOnly = true)))
                verify(!accepts(fact.copy(sourceChatId = 8), message = 11))
                verify(!accepts(fact.copy(validFromMillis = 1_001)))
                verify(!accepts(fact.copy(validUntilMillis = 1_000)))
                verify(accepts(fact.copy(validFromMillis = 1_000, validUntilMillis = 1_001)))
            },
            AppAuditFixture("memory_validity_v1", "Memory validity boundaries and decay arithmetic", listOf("memory_and_rag")) {
                val interval = FactValidity(100, 200)
                verify(!interval.contains(99) && interval.contains(100) && interval.contains(199) && !interval.contains(200))
                verify(!interval.overlaps(FactValidity(200, 300)) && interval.overlaps(FactValidity(199, 300)))
                verify(runCatching { FactValidity(200, 100) }.isFailure)
                verify(memoryHalfLifeWeight(200, 100, 100) == 0.5)
                verify(memoryHalfLifeWeight(100, 200, 100) == 1.0 && memoryHalfLifeWeight(200, null, 100) == 0.0)
                verify(runCatching { memoryHalfLifeWeight(200, 100, 0) }.isFailure)
            },
            AppAuditFixture("memory_graph_v1", "Knowledge graph extraction and bounded recall", listOf("memory_and_rag")) {
                val graph = KnowledgeGraphEngine()
                graph.extractAndStoreFromText("Morgan uses Kotlin.")
                val found = graph.queryContextualFacts("Kotlin")
                verify(found.size == 1 && found.single().relation.relationType == "USES" && found.single().target.name == "Kotlin")
                graph.extractAndStoreFromText("Morgan uses Kotlin.")
                verify(graph.querySubgraph(" MORGAN ").size == 1)
                graph.extractAndStoreFromText("Morgan does not prefer Java.")
                graph.extractAndStoreFromText("Does Morgan use Java?")
                verify(graph.getAllEntities().none { it.id == "java" })
                verify(graph.queryContextualFacts("unrelated fixture query").isEmpty())
                graph.clear()
                verify(graph.getAllEntities().isEmpty() && graph.queryContextualFacts("Kotlin").isEmpty())
            }
        )
    )
