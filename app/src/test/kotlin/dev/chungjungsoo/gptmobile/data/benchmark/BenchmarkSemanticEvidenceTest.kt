package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkSemanticEvidenceTest {
    @Test fun malformedRetainedIntelligenceStaysUngradedWithoutChangingHistoricalGrades() {
        val test = benchmarkSuiteForVersion(9, BenchmarkMode.QUICK).single { it.category == IntelligenceBenchmark.CATEGORY }
        val raw = "{\"{\"Math\":\"138\",\"Logic\":\"B\""
        val oldGrade = IntelligenceBenchmark.evaluateBatch(test, raw, 3)
        val sample = BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.FAILED, canonicalAnswer = raw, answerHash = benchmarkHash(raw), answerLength = raw.length, outputCharacters = raw.length, intelligenceAnswers = oldGrade.answers, intelligenceParseable = false, intelligenceFormatCompliant = false, intelligenceFixtureVersion = 3)
        val run = run(test, sample, 9)
        val result = benchmarkIntelligenceSummary(run)
        assertEquals(6, result.plannedQuestions)
        assertEquals(0, result.gradedQuestions)
        assertEquals(6, result.unknownQuestions)
        assertNull(result.correctQuestions)
        assertNull(result.accuracyPercent)
        assertEquals(0, result.parseableCases)
        assertEquals(1, result.parseabilityObserved)
        assertTrue(sample.intelligenceAnswers.values.all { !it })
        assertEquals(raw, run.samples.single().canonicalAnswer)
        val export = Json.parseToJsonElement(benchmarkExport(Json.encodeToString(BenchmarkBackupPayload(runs = listOf(run))), true)).jsonObject
        val metrics = export.getValue("perRunMetrics").jsonArray.single().jsonObject
        assertEquals(JsonNull, metrics.getValue("intelligence").jsonObject.getValue("accuracyPercent"))
        assertEquals(JsonNull, export.getValue("perRunScores").jsonArray.single().jsonObject.getValue("reasoningReevaluation").jsonArray.single().jsonObject.getValue("correct"))
        assertEquals(oldGrade.answers, run.samples.single().intelligenceAnswers)
    }

    @Test fun missingObjectKeysAreUngradedWhileObservedWrongAnswersRemainWrong() {
        val test = BenchmarkIntelligenceFixtures.batches(0).first()
        val partial = checkNotNull(benchmarkIntelligenceEvidence(test, "{\"Math\":\"999\"}", true))
        assertEquals(BenchmarkSemanticStatus.INCOMPLETE, partial.status)
        assertEquals(1, partial.gradedQuestions)
        assertEquals(0, partial.correctQuestions)
        assertEquals(7, partial.perQuestion.values.count { it == null })
        assertEquals(false, partial.formatCompliant)
        assertEquals(true, partial.parseable)
        val complete = checkNotNull(benchmarkIntelligenceEvidence(test, test.expected, true))
        assertEquals(8, complete.gradedQuestions)
        assertEquals(8, complete.correctQuestions)
        assertEquals(true, complete.formatCompliant)
    }

    @Test fun diagnosticScenarioRateAndPerQuestionIntelligenceHaveDifferentPopulations() {
        val plan = benchmarkSuiteForVersion(9, BenchmarkMode.DIAGNOSTIC)
        val correctByDomain = mapOf("Math" to 0, "Logic" to 4, "Reading" to 5, "Planning" to 2, "Code reasoning" to 2, "Knowledge" to 6)
        val observed = mutableMapOf<String, Int>()
        var scenario = 0
        val samples = plan.map { test ->
            val intel = test.category == IntelligenceBenchmark.CATEGORY
            val number = observed.merge(test.workload, 1, Int::plus)!!
            val correct = if (intel) number <= correctByDomain.getValue(test.workload) else scenario++ < 3
            val answer = if (correct) test.expected ?: "correct" else "WRONG"
            BenchmarkSample(test.id, test.label, test.category, if (correct) BenchmarkOutcome.PASSED else BenchmarkOutcome.FAILED, canonicalAnswer = answer, outputCharacters = answer.length, workload = test.workload)
        }
        val run = BenchmarkRun("diagnostic", "profile", "Profile", "CUSTOM", "model", "key", false, BenchmarkMode.DIAGNOSTIC, 1, suiteVersion = 9, samples = samples, plannedTrials = plan.size, caseManifest = plan)
        val intelligence = benchmarkIntelligenceSummary(run)
        val scenarios = benchmarkDiagnosticScenarioSummary(run)
        assertEquals(36, intelligence.gradedQuestions)
        assertEquals(19, intelligence.correctQuestions)
        assertEquals(100.0 * 19 / 36, intelligence.accuracyPercent!!, .0001)
        assertEquals(0.0, intelligence.domains.getValue("Math"), .0001)
        assertEquals(10, scenarios.graded)
        assertEquals(3, scenarios.passed)
        assertEquals(30.0, scenarios.passPercent!!, .0001)
    }

    @Test fun expandedRotationsKeepAllCasesAndRecordRepeatedQuestionFamilies() {
        val plans = (0L..2L).map(BenchmarkIntelligenceFixtures::questions)
        plans.forEach { questions ->
            assertEquals(48, questions.size)
            assertEquals(8, questions.groupBy { it.domain }.size)
            assertTrue(questions.groupBy { it.domain }.values.all { it.size == 6 })
            assertEquals(48, questions.map { it.familyId }.distinct().size)
        }
        assertEquals(listOf("138", "182", "230"), plans.map { it.single { question -> question.familyId == "math-1" }.expected })
        assertEquals(listOf("2/15", "2/11", "5/22"), plans.map { it.single { question -> question.familyId == "math-2" }.expected })
        assertEquals(listOf("225", "300", "375"), plans.map { it.single { question -> question.familyId == "math-3" }.expected })
        assertEquals(listOf("8", "10", "12"), plans.map { it.single { question -> question.familyId == "math-4" }.expected })
        assertEquals(48, plans.flatten().map { it.familyId }.distinct().size)
        assertTrue(plans.flatten().map { it.contentDigest }.distinct().size > 48)
        assertEquals(50, benchmarkSuiteForVersion(9, BenchmarkMode.FULL).size)
        assertEquals(50, benchmarkSuiteForVersion(10, BenchmarkMode.FULL, 2).size)
        assertEquals(62, benchmarkSuiteForVersion(10, BenchmarkMode.DIAGNOSTIC, 2).size)
    }

    @Test fun providerStructuredFailureKeepsTypedCodeAndRawPrefix() = runTest {
        val test = BenchmarkIntelligenceFixtures.batches(0).first()
        val prefix = "{\"Math\":\"138\","
        val runner = BenchmarkRunner({ _, _ ->
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(prefix), ProviderEvent.Failed("Native grammar rejected reasoning token", errorCode = "CONSTRAINED_DECODING_FAILURE"))
            }
        }, now = { testScheduler.currentTime })
        val sample = runner.run(test, false)
        assertEquals(BenchmarkOutcome.ERROR, sample.outcome)
        assertEquals("CONSTRAINED_DECODING_FAILURE", sample.failureCategory)
        assertEquals(prefix, sample.canonicalAnswer)
        assertEquals(0, sample.intelligenceSemantics!!.gradedQuestions)
        assertFalse(sample.completed)
    }

    private fun run(test: BenchmarkCase, sample: BenchmarkSample, version: Int) = BenchmarkRun("semantic", "profile", "Profile", "CUSTOM", "model", "key", false, BenchmarkMode.QUICK, 1, suiteVersion = version, samples = listOf(sample), caseManifest = listOf(test), plannedTrials = 1)
}
