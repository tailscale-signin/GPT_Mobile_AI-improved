package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewedSessionTest {
    @Test fun singleJsonFenceIsAcceptedButProseAndMissingCoverageStillFail() = runBlocking {
        for (wrapper in listOf<(String) -> String>({ "```json\n$it\n```" }, { "```\n$it\n```" })) {
            val output = GitHubReviewedSession(
                answer(),
                GitHubReviewEvidence().also { receipt(it) },
                "task",
                8192,
                { prompt, _ -> answer(wrapper(verdict(prompt))) },
                {}
            ).streamRound(emptyList(), emptyList()).toList()
            assertEquals(ProviderEvent.Completed, output.last())
        }
        for (bad in listOf("approved", "Before\n{\"approved\":true}", "{\"approved\":true}\n{}", "```json\n{}\n```", """{"approved":"true","reviewed_ids":[],"issues":[],"verified_claims":[]}""")) {
            val output = GitHubReviewedSession(
                answer(),
                GitHubReviewEvidence(),
                "task",
                8192,
                { _, _ -> answer(bad) },
                {}
            ).streamRound(emptyList(), emptyList()).toList()
            assertTrue(output.none { it is ProviderEvent.TextDelta })
            assertEquals("GITHUB_REVIEW_INVALID_VERDICT", output.filterIsInstance<ProviderEvent.Failed>().last().errorCode)
        }
    }

    @Test fun explicitFormatRetryResumesSavedCandidateWithoutRepeatingGeneration() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        var generated = 0
        var reviews = 0
        var valid = false
        fun gate() = GitHubReviewedSession(
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                    generated++
                    emit(ProviderEvent.TextDelta("Retained answer"))
                    emit(ProviderEvent.Completed)
                }
            },
            GitHubReviewEvidence(),
            "task",
            8192,
            { prompt, _ ->
                reviews++
                answer(if (valid) verdict(prompt) else "not JSON")
            },
            {},
            checkpoint = checkpoint()
        )
        assertEquals("GITHUB_REVIEW_INVALID_VERDICT", gate().streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().last().errorCode)
        valid = true
        val resumed = gate().streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("Retained answer"), resumed.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals(1, generated)
        assertEquals(3, reviews)
        assertTrue(checkpoint().load()?.reviewed == true)
    }

    @Test fun malformedReviewRetryBudgetSurvivesRestartAndCannotLoop() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        var generation = 0
        var reviews = 0
        repeat(3) { index ->
            val checkpoint = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
            val gate = GitHubReviewedSession(
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                        generation++
                        emit(ProviderEvent.TextDelta("Saved"))
                        emit(ProviderEvent.Completed)
                    }
                },
                GitHubReviewEvidence(),
                "task",
                8192,
                { _, _ ->
                    reviews++
                    answer("bad format")
                },
                {},
                checkpoint = checkpoint
            )
            val result = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(result.none { it is ProviderEvent.TextDelta })
            val failure = result.filterIsInstance<ProviderEvent.Failed>().last()
            assertEquals(if (index < 2) "GITHUB_REVIEW_INVALID_VERDICT" else "GITHUB_REVIEW_BLOCKED", failure.errorCode)
            assertEquals(index == 0, dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(failure.message).retryAllowed)
            if (index < 2) {
                assertEquals(JsonPrimitive(false), failure.failureDetails?.get("automatic_retry_allowed"))
                assertEquals(JsonPrimitive(true), failure.failureDetails?.get("user_resumable"))
                assertEquals(JsonPrimitive(index != 0), failure.failureDetails?.get("resume_requires_changed_input_or_configuration"))
            }
        }
        assertEquals(1, generation)
        assertEquals(4, reviews)
    }

    @Test fun semanticRejectionCannotUseFormatRetry() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        checkpoint.save("Unsupported draft", emptyList())
        checkpoint.failed("GITHUB_REVIEW_REQUIRED")
        assertFalse(checkpoint.claimFormatRetry())
        val result = GitHubReviewedSession(
            answer(),
            GitHubReviewEvidence(),
            "task",
            8192,
            { _, _ -> error("Rejected claims need changed evidence") },
            {},
            checkpoint = checkpoint
        ).streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", result.filterIsInstance<ProviderEvent.Failed>().last().errorCode)
    }

    @Test fun changedReviewerRechecksCachedRejectionAndRepairsWithoutRepeatingGeneration() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        var generations = 0
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                generations++
                emit(ProviderEvent.TextDelta("All tests passed"))
                emit(ProviderEvent.Completed)
            }
        }
        val reviewCalls = mutableMapOf<String, Int>()
        val repairCalls = mutableMapOf<String, Int>()
        fun gate(revision: String) = GitHubReviewedSession(
            original,
            GitHubReviewEvidence().also { receipt(it) },
            "task",
            8192,
            { prompt, kind ->
                if (kind == "github-review-repair") {
                    repairCalls[revision] = repairCalls.getOrDefault(revision, 0) + 1
                    answer(if (revision == "old-reviewer") "All tests passed" else "Tests failed")
                } else {
                    reviewCalls[revision] = reviewCalls.getOrDefault(revision, 0) + 1
                    val candidate = Json.parseToJsonElement(prompt).jsonObject["candidate_answer"]
                    answer(verdict(prompt, approved = candidate == JsonPrimitive("Tests failed")))
                }
            },
            {},
            checkpoint = checkpoint(),
            reviewerRevision = revision
        )
        val rejected = gate("old-reviewer").streamRound(emptyList(), emptyList()).toList()
        assertEquals("BLOCKED", rejected.filterIsInstance<ProviderEvent.Failed>().single().taskOutcome)
        assertTrue(rejected.none { it is ProviderEvent.TextDelta })
        assertEquals(1, reviewCalls["old-reviewer"])
        assertEquals(1, repairCalls["old-reviewer"])

        val unchanged = gate("old-reviewer").streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", unchanged.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(1, reviewCalls["old-reviewer"])

        val resumed = gate("new-reviewer").streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, resumed.last())
        assertEquals("Tests failed", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(2, reviewCalls["new-reviewer"])
        assertEquals(1, repairCalls["new-reviewer"])
        assertEquals(1, generations)
    }

    @Test fun cachedReviewPassesRequireTheSameTaskAndReviewerAfterReload() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint(task: String = "task") = GitHubReviewCheckpoint(task, { rows[it] }, { key, value -> rows[key] = value })
        val prompt = "complete review input"
        val legacyDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(prompt)
        rows["verdict:$legacyDigest"] = buildJsonObject { put("text", "unbound verdict") }
        rows["repair:$legacyDigest"] = buildJsonObject { put("text", "unbound repair") }
        assertEquals(null, checkpoint().verdict(prompt, "reviewer"))
        assertEquals(null, checkpoint().repair(prompt, "reviewer"))
        assertEquals(null, checkpoint().verdict(prompt, ""))
        assertEquals(null, checkpoint().repair(prompt, ""))

        checkpoint().verdict(prompt, "retained verdict", "reviewer")
        checkpoint().repair(prompt, "retained correction", "reviewer")
        assertEquals("retained verdict", checkpoint().verdict(prompt, "reviewer"))
        assertEquals("retained correction", checkpoint().repair(prompt, "reviewer"))
        assertEquals(null, checkpoint().verdict(prompt, "changed-reviewer"))
        assertEquals(null, checkpoint().repair(prompt, "changed-reviewer"))
        assertEquals(null, checkpoint("different task").verdict(prompt, "reviewer"))
        assertEquals(null, checkpoint("different task").repair(prompt, "reviewer"))
    }

    @Test fun missingRepositoryEvidenceBlocksBeforeReviewAndArrivingEvidenceResumesSavedDraft() = runBlocking {
        val task = "Review the repository and implement the feature"
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint(task, { rows[it] }, { key, value -> rows[key] = value })
        var generations = 0
        var reviews = 0
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                generations++
                emit(ProviderEvent.TextDelta("The requested work needs execution evidence."))
                emit(ProviderEvent.Completed)
            }
        }
        fun gate(evidence: GitHubReviewEvidence) = GitHubReviewedSession(original, evidence, task, 8192, { prompt, _ ->
            reviews++
            answer(verdict(prompt))
        }, {}, checkpoint = checkpoint())
        val initial = gate(GitHubReviewEvidence()).streamRound(emptyList(), emptyList()).toList()
        val failure = initial.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("BLOCKED", failure.taskOutcome)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonArray(listOf(JsonPrimitive("REPOSITORY_EVIDENCE"))), failure.failureDetails?.get("missing_requirements"))
        assertTrue((rows.getValue("active")["review_requirements"] as JsonArray).contains(JsonPrimitive("REPOSITORY_EVIDENCE")))
        assertEquals("The requested work needs execution evidence.", checkpoint().load()?.draft)
        assertEquals(0, reviews)
        assertTrue(initial.none { it is ProviderEvent.TextDelta })

        val unchanged = gate(GitHubReviewEvidence()).streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", unchanged.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(0, reviews)
        val resumed = gate(GitHubReviewEvidence().also { receipt(it) }).streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, resumed.last())
        assertEquals("The requested work needs execution evidence.", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, reviews)
        assertEquals(1, generations)
    }

    @Test fun ordinaryKnowledgeAnswerCanBeReviewedWithoutToolEvidence() = runBlocking {
        var reviews = 0
        val output = GitHubReviewedSession(answer("A branch is a named reference."), GitHubReviewEvidence(), "Explain how to create a branch and push commits", 8192, { prompt, _ ->
            reviews++
            answer(verdict(prompt))
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(1, reviews)
    }

    @Test fun restoredEligibleReceiptsResumeReviewWithoutRefreshingTheirProvenance() = runBlocking {
        val rows = linkedMapOf<String, JsonObject>()
        fun book(now: Long, tools: Set<String>) = GitHubEvidenceBook("same-task", "owner/repo", "main", tools, { now }, load = { rows[it] }, save = { key, value -> rows[key] = value }, retainedIds = { rows.keys.toList() })
        val first = book(1000, setOf("github_context", "github_ci"))
        for (tool in listOf("github_context", "github_ci")) {
            first.observe(ProviderEvent.ToolCall("original-$tool", tool, JsonObject(emptyMap())), AgentToolResult("original-$tool", ToolResultContent.Text("original result from $tool"), false))
        }
        val restored = book(9000, setOf("github_context"))
        restored.restore()
        val receipts = restored.snapshot().first
        val expected = receipts.values.single { it["tool"] == JsonPrimitive("github_context") }
        val evidence = GitHubReviewEvidence()
        val foreign = JsonObject(expected + ("binding" to JsonPrimitive("another-task")))
        evidence.restoreValidated(restored.checkpointBinding(), mapOf("foreign" to foreign) + receipts)
        assertEquals(listOf(expected), evidence.snapshot().map { Json.parseToJsonElement(it).jsonObject })
        assertEquals(JsonPrimitive(1000), expected["observed_at_ms"])
        // A compacted replay must not overwrite the original retained source.
        evidence.observe(ProviderEvent.ToolCall("original-github_context", "github_context", JsonObject(emptyMap())), AgentToolResult("original-github_context", ToolResultContent.Text("short excerpt"), false))
        assertEquals(listOf(expected), evidence.snapshot().map { Json.parseToJsonElement(it).jsonObject })

        val task = "Implement the repository feature"
        val saved = mutableMapOf<String, JsonObject>()
        val checkpoint = GitHubReviewCheckpoint(task, { saved[it] }, { key, value -> saved[key] = value })
        checkpoint.save("Saved candidate", emptyList())
        checkpoint.failed("GITHUB_REVIEW_BLOCKED")
        var reviews = 0
        val noGeneration = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Restored work must not regenerate")
        }
        val output = GitHubReviewedSession(noGeneration, evidence, task, 8192, { prompt, _ ->
            reviews++
            val record = Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.single().jsonObject.getValue("evidence") as JsonPrimitive
            assertEquals(expected, Json.parseToJsonElement(record.content).jsonObject)
            answer(verdict(prompt))
        }, {}, checkpoint = checkpoint).streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(1, reviews)
        assertEquals("Saved candidate", output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun transientProviderFailureKeepsItsIdentityForRunnerRecovery() = runBlocking {
        val failure = ProviderEvent.Failed("Connection reset", errorCode = "NETWORK_ERROR")
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(failure)
        }
        val output = GitHubReviewedSession(source, GitHubReviewEvidence(), "task", 8192, { _, _ -> error("must not review a failed round") }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(failure), output)
    }

    @Test fun actualGatewayCapacityOverridesExpandedPreferenceBeforeReview() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 1_048_576, { _, _ -> error("Review must honor actual capacity") }, {}, currentInputLimit = { 4 }).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    @Test fun oversizedPrivateDraftCancelsSourceBeforeItCanKeepGenerating() = runBlocking {
        var emitted = 0
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                repeat(100) {
                    emitted++
                    emit(ProviderEvent.TextDelta("123456"))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val output = GitHubReviewedSession(source, GitHubReviewEvidence(), "task", 8192, { _, _ -> error("oversized draft must not dispatch review") }, {}, maxDraftCharacters = 10).streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, emitted)
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    @Test fun evidenceFragmentsPreserveUnicodeAtWindowBoundaries() = runBlocking {
        val evidence = GitHubReviewEvidence().also { receipt(it, text = "😀X".repeat(3000)) }
        val expected = evidence.snapshot().single()
        val fragments = mutableListOf<String>()
        val output = GitHubReviewedSession(answer(), evidence, "task", 1600, { prompt, _ ->
            Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.forEach { fragments += (it.jsonObject.getValue("evidence") as JsonPrimitive).content }
            answer(verdict(prompt))
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(expected, fragments.joinToString(""))
        assertFalse(output.any { it is ProviderEvent.Failed })
    }

    @Test fun revokedAuthorityAfterReviewStillPreventsPublication() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { prompt, _ -> answer(verdict(prompt)) }, {}, validatePublication = { error("Permission changed") }).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }
    private fun answer(text: String = "Verified answer") = session(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    private fun verdict(prompt: String, approved: Boolean = true): String = buildJsonObject {
        put("schema_version", GitHubReviewSchema.VERSION)
        put("verdict", if (approved) "PASS" else "FAIL")
        put("reason", if (approved) "The evidence supports these claims." else "The test result contradicts the draft.")
        put("reviewed_ids", JsonArray(Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.map { it.jsonObject.getValue("id") }))
        put("issues", JsonArray(if (approved) emptyList() else listOf(JsonPrimitive("The test failed; do not claim success."))))
        put("verified_claims", JsonArray(listOf(JsonPrimitive("Checked the reported result."))))
    }.toString()
    private fun receipt(evidence: GitHubReviewEvidence, id: String = "c", text: String = "tests failed") {
        evidence.observe(ProviderEvent.ToolCall(id, "github_checks", JsonObject(emptyMap())), AgentToolResult(id, ToolResultContent.Text(text), false))
    }

    @Test fun noDraftIsPublishedUntilAllEvidenceIsReviewed() = runBlocking {
        val evidence = GitHubReviewEvidence().also { receipt(it) }
        val emitted = mutableListOf<ProviderEvent>()
        var reviewed = false
        val gate = GitHubReviewedSession(answer(), evidence, "Check tests", 8192, { prompt, _ ->
            assertTrue(emitted.none { it is ProviderEvent.TextDelta })
            assertTrue(prompt.contains("tests failed"))
            reviewed = true
            answer(verdict(prompt))
        }, {})
        gate.streamRound(emptyList(), emptyList()).collect { emitted += it }
        assertTrue(reviewed)
        assertEquals(listOf("Verified answer"), emitted.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals(ProviderEvent.Completed, emitted.last())
    }

    @Test fun rejectedAnswerIsCorrectedOnceAndRechecked() = runBlocking {
        var reviews = 0
        var repairs = 0
        val output = GitHubReviewedSession(answer("All tests passed"), GitHubReviewEvidence().also { receipt(it) }, "Check tests", 8192, { prompt, kind ->
            if (kind.endsWith("repair")) {
                repairs++
                answer("Tests failed")
            } else {
                reviews++
                answer(verdict(prompt, reviews == 2))
            }
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, reviews)
        assertEquals(1, repairs)
        assertEquals(listOf("Tests failed"), output.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
    }

    @Test fun repeatedRejectionNeverPublishesDraftAndDoesNotLoop() = runBlocking {
        var attempts = 0
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { prompt, kind ->
            attempts++
            if (kind.endsWith("repair")) answer() else answer(verdict(prompt, false))
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, attempts)
        assertTrue(output.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertEquals("GITHUB_REVIEW_REQUIRED", output.filterIsInstance<ProviderEvent.Failed>().last().errorCode)
    }

    @Test fun malformedOrIncompleteReviewFailsClosed() = runBlocking {
        for (review in listOf(answer("approved"), session(ProviderEvent.TextDelta("{}")), session(ProviderEvent.Failed("disconnected")))) {
            val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { _, _ -> review }, {}).streamRound(emptyList(), emptyList()).toList()
            assertTrue(output.any { it is ProviderEvent.Failed })
            assertTrue(output.none { it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        }
    }

    @Test fun reviewMustAcknowledgeEveryResult() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence().also { receipt(it) }, "task", 8192, { _, _ -> answer("""{"approved":true,"reviewed_ids":[],"issues":[],"verified_claims":["checked"]}""") }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    @Test fun toolRoundsKeepRunningWithoutPublishingUnreviewedProse() = runBlocking {
        val call = ProviderEvent.ToolCall("c", "github_decide", JsonObject(emptyMap()))
        val output = GitHubReviewedSession(session(ProviderEvent.TextDelta("Unverified intermediate draft"), call, ProviderEvent.Completed), GitHubReviewEvidence(), "task", 8192, { _, _ -> error("Review must wait for final answer") }, {}).streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(call, ProviderEvent.Completed), output)
    }

    @Test fun cancellationStopsReviewWithoutPublishing() = runBlocking {
        var cancelled = false
        try {
            GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { _, _ -> throw CancellationException("cancel") }, {}).streamRound(emptyList(), emptyList()).toList()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun evidenceOverflowAndContextOverflowNeverDropResults() = runBlocking {
        for ((evidence, context) in listOf(GitHubReviewEvidence(5).also { receipt(it) } to 8192, GitHubReviewEvidence() to 4)) {
            val output = GitHubReviewedSession(answer(), evidence, "task", context, { _, _ -> error("No over-budget review may dispatch") }, {}).streamRound(emptyList(), emptyList()).toList()
            assertTrue(output.any { it is ProviderEvent.Failed })
            assertTrue(output.none { it is ProviderEvent.TextDelta })
        }
    }

    @Test fun longEvidenceIsFullyCoveredAcrossReviewWindows() = runBlocking {
        val evidence = GitHubReviewEvidence().also { receipt(it, text = "evidence ".repeat(2000)) }
        val seen = mutableSetOf<String>()
        var consistency = false
        val output = GitHubReviewedSession(answer(), evidence, "task", 1600, { prompt, kind ->
            Json.parseToJsonElement(prompt).jsonObject.getValue("records").jsonArray.forEach { seen += it.jsonObject.getValue("id").toString() }
            if (kind.endsWith("consistency")) consistency = true
            answer(verdict(prompt))
        }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(seen.size > 1)
        assertTrue(consistency)
        assertFalse(output.any { it is ProviderEvent.Failed })
        assertEquals(ProviderEvent.Completed, output.last())
    }

    @Test fun longCandidateIsReviewedInCompleteBoundedSectionsWithoutRegeneration() = runBlocking {
        val draft = (1..60).joinToString("\n") { "Finding $it: retained evidence supports this bounded observation." }
        val sections = mutableListOf<String>()
        val evidence = GitHubReviewEvidence().also { receipt(it, text = "retained source") }
        val output = GitHubReviewedSession(answer(draft), evidence, "task", 2400, { prompt, kind ->
            assertTrue(prompt.length <= 2400)
            if (kind == "github-review") {
                val root = Json.parseToJsonElement(prompt).jsonObject
                assertEquals(JsonPrimitive("section"), root["candidate_scope"])
                sections += (root.getValue("candidate_answer") as JsonPrimitive).content
            }
            answer(verdict(prompt))
        }, {}, measureInput = { it.length }).streamRound(emptyList(), emptyList()).toList()
        assertTrue(sections.size > 1)
        assertEquals(draft, sections.joinToString(""))
        assertEquals(draft, output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, output.last())
    }

    @Test fun longRejectedCandidateRepairsInBoundedSectionsAndResumesCompletedRepairs() = runBlocking {
        val draft = (1..60).joinToString("\n") { index ->
            "Finding $index: " + (if (index == 31) "status passed" else "evidence retained") + " in the corresponding retained observation."
        }
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        var generated = 0
        var interrupted = false
        var correctedReviews = 0
        val completeRepairs = mutableListOf<String>()
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                generated++
                emit(ProviderEvent.TextDelta(draft))
                emit(ProviderEvent.Completed)
            }
        }
        fun gate() = GitHubReviewedSession(
            original,
            GitHubReviewEvidence().also { receipt(it) },
            "task",
            2400,
            { prompt, kind ->
                assertTrue("Every review and correction must fit the actual input limit", prompt.length <= 2400)
                val root = Json.parseToJsonElement(prompt).jsonObject
                val candidate = (root.getValue("candidate_answer") as JsonPrimitive).content
                if (kind == "github-review-repair") {
                    assertEquals(JsonPrimitive("section"), root["candidate_scope"])
                    if (completeRepairs.size == 1 && !interrupted) {
                        interrupted = true
                        session(ProviderEvent.Failed("Reviewer unavailable", errorCode = "LOCAL_SERVICE_UNREACHABLE"))
                    } else {
                        completeRepairs += prompt
                        answer(candidate.replace("status passed", "status failed"))
                    }
                } else {
                    if (candidate.contains("status failed")) correctedReviews++
                    answer(verdict(prompt, approved = !candidate.contains("status passed")))
                }
            },
            {},
            measureInput = { it.length },
            checkpoint = checkpoint(),
            reviewerRevision = "stable-reviewer"
        )
        val interruptedOutput = gate().streamRound(emptyList(), emptyList()).toList()
        assertTrue(interruptedOutput.none { it is ProviderEvent.TextDelta })
        val interruption = interruptedOutput.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("LOCAL_SERVICE_UNREACHABLE", interruption.errorCode)
        assertEquals(JsonPrimitive("answer_review"), interruption.failureDetails?.get("review_stage"))
        val resumed = gate().streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, resumed.last())
        val published = resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertEquals(draft.replace("status passed", "status failed").lines(), published.lines().filter(String::isNotBlank))
        assertEquals(1, generated)
        assertTrue(correctedReviews > 0)
        assertTrue(completeRepairs.size > 1)
        assertEquals(completeRepairs.distinct(), completeRepairs)
    }

    @Test fun zeroReviewCapacityResumesWhenOnlyLiveInputCapacityChanges() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        var availableInput = 0
        var generations = 0
        var reviews = 0
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = kotlinx.coroutines.flow.flow {
                generations++
                emit(ProviderEvent.TextDelta("Retained answer"))
                emit(ProviderEvent.Completed)
            }
        }
        fun gate() = GitHubReviewedSession(original, GitHubReviewEvidence(), "task", 8192, { prompt, _ ->
            reviews++
            answer(verdict(prompt))
        }, {}, currentInputLimit = { availableInput }, checkpoint = checkpoint(), reviewerRevision = "unchanged-reviewer")
        val initial = gate().streamRound(emptyList(), emptyList()).toList()
        val failure = initial.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("GITHUB_REVIEW_CAPACITY", failure.errorCode)
        assertEquals("BLOCKED", failure.taskOutcome)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonArray(listOf(JsonPrimitive("REVIEW_INPUT_CAPACITY"))), failure.failureDetails?.get("missing_requirements"))
        assertEquals("Retained answer", checkpoint().load()?.draft)
        assertEquals(0, checkpoint().load()?.inputLimit)
        assertTrue(initial.none { it is ProviderEvent.TextDelta })
        assertEquals(0, reviews)

        val unchanged = gate().streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", unchanged.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(0, reviews)
        assertEquals(1, generations)

        availableInput = 8192
        val resumed = gate().streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, resumed.last())
        assertEquals("Retained answer", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, reviews)
        assertEquals(1, generations)
        assertTrue(checkpoint().load()?.reviewed == true)
    }

    @Test fun capacityRecoveryRequiresChangedCapacityAndRetainsTheOriginalCandidate() = runBlocking {
        val rows = mutableMapOf<String, JsonObject>()
        fun checkpoint() = GitHubReviewCheckpoint("task", { rows[it] }, { key, value -> rows[key] = value })
        checkpoint().save("Retained answer", emptyList(), inputLimit = 4)
        checkpoint().failed("GITHUB_REVIEW_CAPACITY")
        val blocked = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 4, { _, _ -> error("Unchanged capacity cannot retry") }, {}, checkpoint = checkpoint())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals("GITHUB_REVIEW_BLOCKED", blocked.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        val noRegeneration = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Saved candidate must be reused")
        }
        val resumed = GitHubReviewedSession(noRegeneration, GitHubReviewEvidence(), "task", 8192, { prompt, _ -> answer(verdict(prompt)) }, {}, checkpoint = checkpoint())
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals("Retained answer", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, resumed.last())
    }

    @Test fun fullReceiptWinsOverLaterReplayExcerpt() {
        val evidence = GitHubReviewEvidence()
        receipt(evidence, text = "complete receipt")
        receipt(evidence, text = "short replay")
        assertTrue(evidence.snapshot().single().contains("complete receipt"))
        assertFalse(evidence.snapshot().single().contains("short replay"))
    }

    @Test fun reviewerCannotRequestTools() = runBlocking {
        val output = GitHubReviewedSession(answer(), GitHubReviewEvidence(), "task", 8192, { _, _ -> session(ProviderEvent.ToolCall("write", "github_commit", JsonObject(emptyMap())), ProviderEvent.Completed) }, {}).streamRound(emptyList(), emptyList()).toList()
        assertTrue(output.any { it is ProviderEvent.Failed })
        assertTrue(output.none { it is ProviderEvent.TextDelta || it is ProviderEvent.ToolCall })
    }
}
