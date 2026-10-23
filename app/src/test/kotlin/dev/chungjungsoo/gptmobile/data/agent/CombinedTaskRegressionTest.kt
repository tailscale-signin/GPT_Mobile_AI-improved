package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedContributionUsability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedTaskRegressionTest {
    @Test fun shortContributionsAreNotRejectedWhenTaskMeaningIsUncertain() {
        assertTrue(CombinedContributionUsability.assess("COMPLETED", "Useful contribution.", request = "Request").admitted)
        assertTrue(CombinedContributionUsability.assess("COMPLETED", "4", request = "What is two plus two?").admitted)
        assertFalse(CombinedContributionUsability.assess("COMPLETED", "Germany", request = "History of Germany").admitted)
    }

    @Test fun comparisonInventoryExcludesAnUnrelatedDomainWithoutLosingContributors() {
        val sources = (1..12).map { p -> CombinedModelResponse("p$p", "Profile $p", content = (1..25).joinToString(" ") { "Provider $it offers a free model API for chat applications." } + " Archaeologists discovered ancient pottery near excavation site $p.") }
        val request = "Rank the best free model APIs."
        val rows = CombinedEditorialInventory.select(CombinedClaimSemantics.records(sources), request, CombinedAnswerPolicy.from(request))
        assertEquals(25, rows.size)
        assertTrue(rows.all { it.profileUids.size == 12 })
        assertFalse(rows.any { it.proposition.contains("Archaeologists") })
    }

    @Test fun explicitNewSubjectDoesNotInheritAConjunctionOrRewriteVerb() {
        assertTrue(CombinedTaskContract.relevantHistoryIndices("History of Germany that covers modern developments", listOf("History of Portugal")).isEmpty())
        assertTrue(CombinedTaskContract.relevantHistoryIndices("Rewrite the history of Germany", listOf("History of Portugal")).isEmpty())
        assertTrue(CombinedTaskContract.from("What is 2+2?").violations("4").isEmpty())
    }

    @Test fun explicitComparisonResolvesAChainOfFollowups() {
        val history = listOf("History of Germany", "Continue")
        val retained = CombinedTaskContract.relevantHistoryIndices("Compare it with Portugal", history).map { history[it] }
        val contract = CombinedTaskContract.from("Compare it with Portugal", retained.joinToString("\n"))
        assertEquals(setOf("germany", "portugal"), contract.subjects.map { it.lowercase() }.toSet())
    }

    private val germany = "Germany's early history includes Germanic societies and contact with ancient Rome. " +
        "Medieval German territories formed much of the Holy Roman Empire. " +
        "The Reformation transformed religious and political life during the early modern period. " +
        "Unification in 1871 created a German Empire under Prussian leadership. " +
        "The Nazi dictatorship caused catastrophic war and the Holocaust in the twentieth century. " +
        "Reunification in 1990 joined East and West Germany, and the country remains part of the European Union."
    private val portugal = "Portugal developed as a medieval kingdom. Portuguese maritime exploration expanded in the fifteenth century."
    private fun session(text: String, terminal: ProviderEvent = ProviderEvent.Completed) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.TextDelta(text))
            emit(terminal)
        }
    }

    @Test fun francePortugalGermanyRejectsWrongSubjectAndStartsFreshRepair() = runTest {
        val requests = listOf("Give me a 500 word essay on the history of France.", "Give me the history of Portugal.")
        assertEquals(listOf("france"), CombinedAnswerPolicy.from(requests.first()).task!!.subjects)
        val interrupted = CombinedDeliverySession(session(portugal, ProviderEvent.Failed("COMBINED_PARTIAL: output limit")), listOf(CombinedModelResponse("p", "Portugal", content = portugal)), 10000, validateAccess = {}, maxRepairRounds = 0, originalRequest = requests.last()) { _, _ -> error("No repair") }
        val interruptedEvents = interrupted.streamRound(emptyList(), emptyList()).toList()
        assertFalse(interruptedEvents.filterIsInstance<ProviderEvent.CombinedQuality>().last().report.complete)
        assertTrue(interruptedEvents.filterIsInstance<ProviderEvent.TextDelta>().any { it.text == portugal })
        assertEquals(emptyList<Int>(), CombinedTaskContract.relevantHistoryIndices("History of Germany", requests))
        val sources = listOf(CombinedModelResponse("g", "Germany draft", content = germany))
        var repairs = 0
        val delivery = CombinedDeliverySession(session(portugal), sources, 10000, validateAccess = {}, originalRequest = "History of Germany", repairDraft = { repair ->
            repairs++
            assertTrue("TASK_SUBJECT_MISMATCH" in repair.failedChecks)
            val packet = buildCombinedReplacementPrompt("History of Germany", portugal, repair.answerContract, repair.failedChecks)
            assertFalse(packet.contains(portugal))
            session(germany)
        }) { _, _ -> error("Use targeted repair") }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertEquals(germany, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.filterIsInstance<ProviderEvent.CombinedQuality>().last().report.complete)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun unresolvedWrongTopicCannotCompleteEvenWithNormalProviderStop() = runTest {
        val sources = listOf(CombinedModelResponse("g", "Germany draft", content = germany))
        val delivery = CombinedDeliverySession(session(portugal), sources, 10000, validateAccess = {}, maxRepairRounds = 0, originalRequest = "History of Germany") { _, _ -> error("No repair") }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        val report = events.filterIsInstance<ProviderEvent.CombinedQuality>().last().report
        assertFalse(report.complete)
        assertTrue(report.correctionRequired)
        assertTrue(events.last() is ProviderEvent.Failed)
        assertEquals(germany, sources.single().content)
    }

    @Test fun followUpResolvesBothSubjectsWithoutTreatingNewTaskAsContinuation() {
        val task = CombinedTaskContract.from("Compare it with Portugal", "History of Germany")
        assertEquals("FOLLOW_UP", task.relationship)
        assertEquals(setOf("portugal", "germany"), task.subjects.toSet())
        assertTrue(task.violations(portugal).isNotEmpty())
        assertTrue(task.violations("Germany and Portugal followed different political trajectories.").isEmpty())
        assertEquals(listOf(1), CombinedTaskContract.relevantHistoryIndices("Compare it with Portugal", listOf("History of France", "History of Germany")))
        assertEquals("NEW_TASK", CombinedTaskContract.from("History of Germany", "History of Portugal").relationship)
    }

    @Test fun coverageCompressionKeepsPeriodsWithoutRepeatingSubjectWords() {
        val source = CombinedModelResponse("g", "Germany draft", content = germany + "\n" + (1..80).joinToString("\n") { "Germany has a rich and complex history, introduction number $it." })
        val policy = CombinedAnswerPolicy.from("Give me a 500 word essay on the history of Germany.")
        val inventory = CombinedEditorialInventory.select(CombinedClaimSemantics.records(listOf(source)), policy.task!!.request, policy)
        assertTrue(inventory.size <= 25)
        assertEquals((0..5).toSet(), inventory.flatMap { CombinedHistoryOutline.periods(it.proposition) }.toSet())
        val assessment = CombinedFinalCheck.check(germany.substringBefore("Reunification"), listOf(source), CombinedEvidenceLedger(), policy)
        assertTrue(assessment.checks.any { it.id.startsWith("TASK_COVERAGE_UNMET:Recent") && it.material })
    }

    @Test fun gibberishRepetitionAndFactualDemoCannotEnterInventory() {
        assertFalse(CombinedContributionUsability.assess("COMPLETED", "purple teapot orbit banana ".repeat(30), request = "History of Germany").admitted)
        assertFalse(CombinedContributionUsability.assess("COMPLETED", "Germany blue banana revolving forever. ".repeat(25), request = "History of Germany").admitted)
        assertFalse(CombinedContributionUsability.assess("COMPLETED", germany, request = "History of Germany", demo = true).admitted)
        assertTrue(CombinedContributionUsability.assess("COMPLETED", germany, request = "History of Germany").admitted)
        assertTrue(CombinedContributionUsability.assess("COMPLETED", "Violet engines dream beneath a silver sea.", request = "Write an imaginative poem", demo = true).admitted)
    }

    @Test fun fiveHundredWordEssayHasTruthfulToleranceAndCompleteScope() = runTest {
        val text = franceEssay.trimIndent().replace("\n\n", " ")
        val policy = CombinedAnswerPolicy.from("Give me a 500 word essay on the history of France.")
        assertTrue("words=${CombinedWordCounter.count(text)}", CombinedWordCounter.count(text) in 450..550)
        assertTrue(policy.violations(text).isEmpty())
        val delivery = CombinedDeliverySession(session(text), listOf(CombinedModelResponse("f", "France", content = text)), 20000, validateAccess = {}, originalRequest = policy.task!!.request) { _, _ -> error("Complete answer must not need a repair") }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.CombinedQuality>().last().report.complete)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    private val franceEssay = """
        France's history reflects successive changes in territory, government, religion, and social life. Ancient Gaul contained diverse Celtic communities before Roman conquest brought roads, cities, administration, and Latin. Roman institutions transformed the region, but local traditions persisted. Christianity spread during late antiquity, establishing religious networks that survived the weakening of imperial authority in western Europe.

        Frankish kingdoms emerged after Roman rule. Clovis consolidated power and adopted Catholic Christianity, strengthening connections between monarchy and church. Charlemagne later ruled a large western European empire, but its division contributed to the development of a western kingdom. Medieval France gradually took shape through competing dynasties, regional loyalties, and expanding royal authority rather than an immediate national settlement.

        The Capetian monarchy increased its influence over powerful nobles. Agricultural development, growing towns, universities, and Gothic cathedrals changed medieval society. The Hundred Years' War brought devastation and struggles over succession, while Joan of Arc became associated with renewed French resistance. By the late Middle Ages, royal power had strengthened, although regional identities and privileges remained significant.

        Renaissance culture connected France with wider European intellectual and artistic movements. Religious conflict between Catholics and Protestants fractured the kingdom during the sixteenth century. Later Bourbon rulers expanded centralized government. Louis XIV made Versailles a symbol of monarchy, while costly wars and unequal taxation placed burdens on society. Enlightenment thinkers questioned inherited authority and debated political rights.

        Financial crisis and social grievances contributed to the Revolution of 1789. Revolutionaries challenged aristocratic privilege, proclaimed rights, and transformed political institutions. The monarchy fell, but revolutionary government also involved violence, civil conflict, and war. Napoleon subsequently consolidated authority, introduced durable legal and administrative reforms, and built an empire whose military expansion ended in defeat.

        The nineteenth century brought repeated changes between monarchy, empire, and republic. Industrialization, urban growth, and expanding education reshaped daily life. Workers organized for political and economic rights, while republican institutions developed unevenly. France also expanded an overseas colonial empire, imposing domination and exploitation whose consequences continued long after formal colonial rule ended.

        The First World War caused immense destruction and loss of life. During the Second World War, German occupation and the collaborationist Vichy regime coexisted with resistance inside France and abroad. Liberation did not erase these divisions. Postwar reconstruction, social reforms, and economic growth changed society, while decolonization involved difficult conflicts, especially in Indochina and Algeria.

        The Fifth Republic, established in 1958, strengthened the presidency. France became a central participant in European integration and remained influential in diplomacy and culture. Immigration, changing gender roles, and social movements transformed public life. Contemporary debates concern inequality, secularism, identity, environmental policy, and the balance between national sovereignty and European cooperation. These continuing disputes show that French history remains an unfinished negotiation over citizenship and power.
    """
}
