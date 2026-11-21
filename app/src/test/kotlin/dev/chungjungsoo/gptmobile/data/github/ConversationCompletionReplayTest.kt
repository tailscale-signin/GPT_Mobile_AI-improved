package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompletionReplayTest {
    private val commit = "a".repeat(40)

    private fun source(vararg texts: String) = buildJsonObject {
        put("commit_sha", commit)
        put(
            "items",
            JsonArray(
                texts.mapIndexed { index, content ->
                    buildJsonObject {
                        put("path", "File$index.kt")
                        put("commit_sha", commit)
                        put("sha", GitHubCommitReconciliation.blobSha(content))
                        put("start_line", 1)
                        put("end_line", content.count { it == '\n' } + 1)
                        put("content", content)
                    }
                }
            )
        )
    }

    @Test fun savedRangesDeliverConsecutiveUnicodeSourceAcrossFilesAndRestore() {
        val texts = listOf((1..130).joinToString("\n") { "val line$it = \"shared read 📘\"" }, "α😀".repeat(1600), "")
        val data = source(*texts.toTypedArray())
        val args = buildJsonObject { put("commit_sha", commit) }
        var views = GitHubResultViews(pageBytes = 4096)
        var result = views.present("scope", "github_read", "github_read", AgentToolResult("batch", ToolResultContent.Json(data), false), args)
        val delivered = linkedMapOf<String, String>()
        val cursors = mutableSetOf<String>()
        do {
            val page = (result.content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive(true), page["source_page"])
            assertEquals(JsonPrimitive(commit), page["commit_sha"])
            assertTrue(page.toString().encodeToByteArray().size <= 4096)
            assertFalse("fragments" in page)
            page.getValue("items").jsonArray.forEach { raw ->
                val row = raw.jsonObject
                val path = row.getValue("path").jsonPrimitive.content
                val before = delivered[path].orEmpty()
                assertEquals(JsonPrimitive(before.length), row["range_offset"])
                assertEquals(JsonPrimitive(1 + before.count { it == '\n' }), row["start_line"])
                delivered[path] = before + row.getValue("content").jsonPrimitive.content
            }
            val cursor = (page["continuation_call"] as? JsonObject)?.get("arguments")?.jsonObject?.get("view_cursor")?.jsonPrimitive?.contentOrNull ?: break
            assertTrue(cursors.add(cursor))
            assertTrue("source should occupy most of each page", cursors.size < 10)
            val snapshot = requireNotNull(views.checkpoint("scope", result)).second
            views = GitHubResultViews(pageBytes = 4096)
            assertFalse(views.restore("other", "github_read", "github_read", cursor, snapshot))
            assertTrue(views.restore("scope", "github_read", "github_read", cursor, snapshot))
            result = views.read("scope", "github_read", "github_read", cursor, "saved")
            assertFalse(result.dispatched)
        } while (true)
        assertEquals(texts, delivered.values.toList())
    }

    @Test fun immutableCachedExcerptSurvivesProjectionWhileMutableOrMismatchedSourceStaysUnverified() {
        val result = source("val answer = 42")
        val raw = buildJsonObject {
            put("tool", "github_read")
            put("trusted", true)
            put("success", true)
            put("immutable", true)
            put("observed_at_ms", JsonNull)
            put("source_head_sha", commit)
            put("arguments", buildJsonObject { put("commit_sha", commit) })
            put("result", result)
            put("result_sha256", GitHubEvidenceBook.digest(result.toString()))
        }
        val claim = buildJsonObject {
            put("receipt_id", "r")
            put("pointer", "/items/0/content#L1-L1")
            put("value", "val answer = 42")
        }
        fun verify(row: JsonObject) = GitHubEvidencePolicy.verify(claim, mapOf("r" to row), 200_000, 4)
        for (row in listOf(raw, GitHubEvidenceProjection.receipt(raw))) {
            assertEquals(JsonPrimitive("VERIFIED"), verify(row)["status"])
            assertEquals(JsonPrimitive("PINNED_SOURCE_IDENTITY"), verify(row)["freshness_basis"])
            assertEquals(JsonNull, verify(row)["observed_at_ms"])
            for ((field, value) in mapOf("immutable" to JsonPrimitive(false), "source_head_sha" to JsonPrimitive("b".repeat(40)), "result_sha256" to JsonPrimitive("0".repeat(64)))) {
                assertEquals(JsonPrimitive("STALE"), verify(JsonObject(row + (field to value)))["status"])
            }
            assertEquals(JsonPrimitive("NOT_MEASURED"), verify(JsonObject(row + ("verification_eligible" to JsonPrimitive(false))))["status"])
        }
    }

    @Test fun expiredWindowRequiresExplicitFreshUserInstructionAndSurvivesRestart() = runBlocking {
        var clock = 1_000_000L
        val stored = mutableMapOf<String, JsonObject>()
        fun open() = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), now = { clock }, load = { stored[it] }, save = { k, v -> stored[k] = v }, latestDeadlineGrant = { stored["deadline_grant_13"] })
        val task = "Review the branch. Budget: 1 minute."
        val first = open()
        first.retainTaskContract(task, 10, emptyList(), 9)
        clock += 60_000
        first.authorizeTimeWindow("continue", 11, clock / 1000)
        assertEquals(0L, first.remainingTaskMillis())
        first.authorizeTimeWindow("Continue for 10 minutes", 13, clock / 1000)
        assertEquals(600_000L, first.remainingTaskMillis())
        val restored = open()
        restored.retainTaskContract(task, 10, emptyList(), 9)
        clock += 10_000
        restored.authorizeTimeWindow("Continue for 10 minutes", 13, clock / 1000)
        assertEquals(590_000L, restored.remainingTaskMillis())
        assertEquals(JsonPrimitive(1_060_000L), stored.getValue("task_contract")["deadline_at_ms"])
        assertEquals(task, restored.acceptedTaskRequest())
    }

    @Test fun knownPrNumbersAreTypedAndIsolatedFromOtherRepositoriesAndIssues() {
        fun receipt(tool: String, role: String = "WRITE_DESTINATION") = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", tool)
            put("repository_role", role)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "reviews")
                    put("pull_number", "1970")
                }
            )
            put("result", buildJsonObject { put("reviews", JsonArray(emptyList())) })
        }
        assertEquals(JsonPrimitive(1970), GitHubSelfTestReads.pullNumber(listOf(receipt("github_pr"))))
        assertNull(GitHubSelfTestReads.pullNumber(listOf(receipt("github_issue"), receipt("github_pr", "READ_SOURCE"))))
        assertNotNull(GitHubExecutionLimits.renewedDuration("Continue for 10 minutes"))
        assertNull(GitHubExecutionLimits.renewedDuration("The source says: continue for 10 minutes"))
    }

    @Test fun aTightProviderRequestStillContainsExactSourceAndItsRecoveryHandle() {
        val code = (1..200).joinToString("\n") { "val name$it = \"📘\"" }
        val views = GitHubResultViews(pageBytes = 4096)
        val initial = views.present("scope", "github_read", "github_read", AgentToolResult("original", ToolResultContent.Json(source(code)), false))
        val compact = (dev.chungjungsoo.gptmobile.data.agent.GitHubReplayRecovery.compact(initial.content, 1200) as ToolResultContent.Json).value.jsonObject
        val excerpt = compact.getValue("items").jsonArray.single().jsonObject
        assertTrue(compact.toString().length <= 1200)
        val text = excerpt.getValue("content").jsonPrimitive.content
        assertTrue(text.isNotBlank() && code.startsWith(text))
        assertEquals(JsonPrimitive(false), excerpt["range_end"])
        assertEquals(JsonPrimitive(true), compact["recovery_required"])
        val cursor = compact.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        assertFalse(views.read("scope", "github_read", "github_read", cursor, "recover").isError)
        fun roleRequest(role: String) = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", role)
                            put("tool_call_id", "original")
                            put("content", compact.toString())
                        }
                    )
                )
            )
        }
        val evidence = requireNotNull(dev.chungjungsoo.gptmobile.data.network.SerializedToolSourceRanges.from(roleRequest("tool"))).single().jsonObject
        assertEquals(JsonPrimitive(text.length), evidence["length_utf16"])
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(text)), evidence["excerpt_sha256"])
        assertFalse(evidence.toString().contains("File0.kt"))
        assertNull(dev.chungjungsoo.gptmobile.data.network.SerializedToolSourceRanges.from(roleRequest("user")))
    }

    @Test fun interruptedTurnRetainsOnlyPublicCheckpointText() {
        fun entry(type: dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType, text: String) =
            dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(type, content = text, progressCheckpoint = true)
        val rows = listOf(
            entry(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, "The build failed before compilation."),
            entry(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.THINKING, "hidden analysis"),
            entry(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TOOL, "private tool payload").copy(toolSequence = 1)
        )
        val report = dev.chungjungsoo.gptmobile.data.agent.interruptedConversationReport(rows)
        assertTrue(report.contains("failed before compilation") && report.contains("1 tool entry"))
        assertFalse(report.contains("hidden") || report.contains("payload"))
    }

    @Test fun suppressedSuccessAndUnknownCallIdsDoNotAdvanceAnInvestigation() {
        val progress = dev.chungjungsoo.gptmobile.data.agent.GitHubInvestigationProgress()
        fun batch(round: Int): dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange {
            val call = dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.ToolCall("call-$round", "github_read", buildJsonObject { put("action", "ranges") })
            return dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange(
                listOf(call),
                listOf(
                    AgentToolResult("unrelated", ToolResultContent.Text("noise"), false),
                    AgentToolResult(call.callId, ToolResultContent.Json(buildJsonObject { put("schema", "github.repeat.v1") }), false)
                )
            )
        }
        repeat(3) { assertNull(progress.observe(batch(it))) }
        assertNotNull(progress.observe(batch(4)))
    }

    @Test fun outcomeFiltersRequireSuccessfulHistoricalExecutionAndExplicitFeedback() {
        val id = "ev_" + "1".repeat(24)
        assertEquals("build" to "gateway", GitHubSavedOutcome.query("outcome:build gateway"))
        assertEquals(id, GitHubSavedOutcome.annotation("feedback: failed $id still hangs")?.receipt)
        assertNull(GitHubSavedOutcome.annotation("If I say feedback: worked $id"))
        fun observed(tool: String, result: JsonObject) = buildJsonObject {
            put("schema", "github.evidence.v1")
            put("trusted", true)
            put("success", true)
            put("tool", tool)
            put("arguments", buildJsonObject { put("action", "status") })
            put("result", result)
        }
        val run = buildJsonObject {
            put("status", "completed")
            put("conclusion", "success")
            put("head_sha", commit)
            put("id", 42)
            put("name", "Build signed APK")
        }
        assertEquals("build", GitHubSavedOutcome.category(observed("github_ci", run)))
        assertNull(GitHubSavedOutcome.category(observed("github_ci", JsonObject(run + ("conclusion" to JsonPrimitive("failure"))))))
        assertNull(GitHubSavedOutcome.category(JsonObject(observed("github_ci", run) + ("trusted" to JsonPrimitive(false)))))
        assertEquals(
            "pr",
            GitHubSavedOutcome.category(
                observed(
                    "github_pr",
                    buildJsonObject {
                        put("number", 72)
                        put("merged", true)
                    }
                )
            )
        )
        assertNull(
            GitHubSavedOutcome.category(
                observed(
                    "github_pr",
                    buildJsonObject {
                        put("number", 72)
                        put("state", "closed")
                    }
                )
            )
        )
    }

    @Test fun compareLosslessPagesWithFilePagesAtTheSameAllowance() {
        val raw = source(*(1..4).map { file -> (1..240).joinToString("\n") { "val row$it = \"file $file [📘] — example\"" } }.toTypedArray())
        fun replay(detail: String): Pair<Int, Int> {
            val views = GitHubResultViews(pageBytes = 4096)
            var page = views.present("session", "github_read", "github_read", AgentToolResult("original", ToolResultContent.Json(raw), false), buildJsonObject { put("response_detail", detail) })
            val cursors = mutableSetOf<String>()
            var bytes = 0
            do {
                val current = (page.content as ToolResultContent.Json).value.jsonObject
                bytes += current.toString().encodeToByteArray().size
                val next = (current["continuation_call"] as? JsonObject)?.get("arguments")?.jsonObject?.get("view_cursor")?.jsonPrimitive?.content ?: break
                assertTrue(cursors.add(next) && cursors.size <= 400)
                page = views.read("session", "github_read", "github_read", next, "page-" + cursors.size)
                assertFalse(page.isError)
            } while (true)
            return cursors.size + 1 to bytes
        }
        val targeted = GitHubResultViews(pageBytes = 4096)
        val first = (targeted.present("scope", "github_read", "github_read", AgentToolResult("batch", ToolResultContent.Json(raw), false)).content as ToolResultContent.Json).value.jsonObject
        val file = first.getValue("range_calls").jsonArray.map { it.jsonObject }.single { it["path"] == JsonPrimitive("File3.kt") }
        val cursor = file.getValue("call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        assertTrue(targeted.read("other", "github_read", "github_read", cursor, "foreign").isError)
        val selected = (targeted.read("scope", "github_read", "github_read", cursor, "select").content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive("File3.kt"), selected.getValue("items").jsonArray.first().jsonObject["path"])
        val rawPages = replay("full")
        val filePages = replay("compact")
        assertTrue("raw=$rawPages file=$filePages", filePages.first < rawPages.first && filePages.second < rawPages.second)
        println("Four-file replay, 4096-byte page budget: raw=$rawPages file=$filePages (pages, serialized UTF-8 bytes)")
    }

    @Test fun unchangedBlockerRetainsEarlierSubstantiveReportButKeepsItsNewVerdict() = runBlocking {
        val records = mutableMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("work", "owner/repo", "topic", emptySet(), load = { records[it] }, save = { k, v -> records[k] = v })
        val (receipts, epoch) = book.snapshot()
        val prior = buildJsonObject { put("claims", JsonArray(listOf(buildJsonObject { put("status", "VERIFIED") }))) }
        val blocked = buildJsonObject {
            put("progress_state", "BLOCKED_UNCHANGED")
            put("completion_verified", false)
        }
        assertTrue(book.retainAnswerIfCurrent("The build failed before tests started.", prior, epoch, receipts.keys))
        assertTrue(book.retainAnswerIfCurrent("Waiting for the baseline.", blocked, epoch, receipts.keys))
        assertEquals("Waiting for the baseline.", book.retainedAnswer()?.first)
        val earlier = requireNotNull(book.retainedAnswer(includeEarlierFindings = true))
        assertTrue(earlier.first.contains("before tests started") && earlier.first.contains("Waiting for the baseline"))
        assertEquals(blocked, earlier.second)
    }

    @Test fun planningUsesDependenciesAndSeparatesInspectionFromPublication() {
        fun ids(task: String) = GitHubOperationPlanner.plan(task).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val inspect = ids("Review the staged changes, release notes and merge policy. Read-only.")
        assertFalse(inspect.any { it in setOf("stage_patch", "commit_staged_patch", "merge_gate", "publish_release") })
        val repair = ids("Fix the defect and open a draft PR. Do not publish or merge.")
        assertTrue(repair.containsAll(listOf("prerequisites", "select_defect", "regression", "stage_patch", "validation", "draft_pr")))
        assertTrue(repair.indexOf("select_defect") < repair.indexOf("stage_patch"))
        assertFalse(repair.any { it == "publish_release" || it == "merge_gate" })
        assertTrue("publish_release" in ids("Publish a release"))
        assertTrue("publish_release" in ids("Fix the build, then publish this release"))
        assertFalse("publish_release" in ids("Publish the PR comment"))
        val withBuild = ids("Fix the build failure")
        assertEquals(withBuild.distinct(), withBuild)
    }

    @Test fun conversationMeasurementRequiresVerifiedDeliveryAndKeepsProviderWaitsAndPartialWorkDistinct() {
        val linked = JsonPrimitive("ev_" + "a".repeat(24))
        fun verdict(delivered: Boolean, finalState: String = "PASS") = buildJsonObject {
            put("task_outcome", "COMPLETED")
            put("completion_verified", true)
            put("response_delivered", delivered)
            put(
                "acceptance_ledger",
                buildJsonObject {
                    put("schema", "github.acceptance-ledger.v1")
                    put("completion_verified", true)
                    put(
                        "requirements",
                        JsonArray(
                            listOf("PASS", finalState).mapIndexed { index, state ->
                                buildJsonObject {
                                    put("id", index)
                                    put("required", true)
                                    put("state", state)
                                    put("receipt_ids", JsonArray(if (index == 0) listOf(linked) else emptyList()))
                                }
                            }
                        )
                    )
                }
            )
        }
        val pass = GitHubWorkMeasurement.from(verdict(true))
        assertTrue(pass.completed)
        assertEquals(1, pass.passing)
        assertFalse(GitHubWorkMeasurement.from(verdict(false)).completed)
        assertFalse(GitHubWorkMeasurement.from(verdict(true, "BLOCKED")).completed)
        val wait = GitHubWorkMeasurement.from(null, "WAITING_PROVIDER_LIMIT")
        assertEquals("WAITING_FOR_PROVIDER", wait.state)
        assertEquals("INTERRUPTED", GitHubWorkMeasurement.from(null, "INTERRUPTED").state)
        val deadline = buildJsonObject { put("stop_reason", buildJsonObject { put("code", "ACCEPTED_TASK_DEADLINE_REACHED") }) }
        assertEquals("PAUSED", GitHubWorkMeasurement.from(deadline).state)
        assertTrue(GitHubConversationWork(listOf(pass, wait), false).description().contains("1 of 2 retained tasks"))
    }

    @Test fun taskSteeringAndSearchLinksStayBoundToTheirIntendedWork() {
        assertTrue(GitHubTaskContinuity.reportOnly("How is this task going?"))
        assertFalse(GitHubTaskContinuity.reportOnly("What is the status of PR #42?"))
        val steering = dev.chungjungsoo.gptmobile.data.queue.ConversationSteering.Companion
        assertEquals("CORRECTION", steering.from("Actually, keep the previous baseline").name)
        assertEquals("CANCEL", steering.from("Please cancel this task.").name)
        assertEquals("ADDITION", steering.from("Explain how to cancel this task").name)
        val feedback = buildJsonObject {
            put("kind", "user_outcome")
            put("chat_id", 7)
            put("task_id", "7:42")
        }
        assertEquals(42, GitHubSavedOutcome.messageId(feedback, 7))
        assertNull(GitHubSavedOutcome.messageId(feedback, 8))
    }

    @Test fun observedWorkOnlyCountsOverlappingIntervalsOnceWithinTheDecisionWindow() {
        assertEquals(50L, observedGitHubWorkMillis(10, 80, listOf(0L to 20L, 15L to 40L, 60L to 120L)))
        assertNull(observedGitHubWorkMillis(10, 80, emptyList()))
    }
}
