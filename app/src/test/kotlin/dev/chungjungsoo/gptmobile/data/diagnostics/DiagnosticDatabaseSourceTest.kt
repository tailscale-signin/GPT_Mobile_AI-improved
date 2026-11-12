package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import dev.chungjungsoo.gptmobile.presentation.ui.chat.writeStagedChatDiagnostics
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticDatabaseSourceTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var research: ResearchDatabase
    private lateinit var workspace: WorkspaceRepository
    private lateinit var source: DiagnosticDatabaseSource
    private val values = mutableMapOf<String, ByteArray>()
    private val vault = object : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun storedSize(secretRef: String) = values[secretRef]?.size?.plus(38)?.toLong()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        research = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ResearchDatabase::class.java).build()
        workspace = WorkspaceRepository(database, vault)
        source = DiagnosticDatabaseSource(database, research, workspace)
    }

    @After fun close() {
        database.close()
        research.close()
    }

    @Test fun byteBoundSkipsOversizedUnicodeWhileRetainingOtherRowsAndChatScope() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        val other = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Other")).toInt()
        database.messageDao().addMessages(
            MessageV2(chatId = chat, content = "漢".repeat(600), platformType = null),
            MessageV2(chatId = chat, content = "retained message", platformType = null),
            MessageV2(chatId = other, content = "other chat private message", platformType = null)
        )
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "conversation", 1024, {}, { rows += it }, omissions::add)
        assertTrue(omissions.contains("RECORD_BYTE_LIMIT"))
        assertTrue(rows.toString().contains("retained message"))
        assertFalse(rows.toString().contains("漢"))
        assertFalse(rows.toString().contains("other chat private message"))
    }

    @Test fun newRowsAfterCaptureStartsDoNotMoveTheHighWatermark() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        repeat(20) { database.messageDao().addMessages(MessageV2(chatId = chat, content = "original-$it", platformType = null)) }
        val rows = mutableListOf<JsonObject>()
        source.capture(chat, "conversation", 4096, {}, { row ->
            rows += row
            if (rows.size == 1) database.messageDao().addMessages(MessageV2(chatId = chat, content = "later row", platformType = null))
        }, {})
        assertEquals(20, rows.size)
        assertFalse(rows.toString().contains("later row"))
        assertEquals(1, rows.map { it["tableHighWatermark"] }.distinct().size)
    }

    @Test fun durableDatabaseCursorResumesAfterCommittedRowsWithoutMovingItsBoundary() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Cursor resume")).toInt()
        repeat(40) { database.messageDao().addMessages(MessageV2(chatId = chat, content = "original-$it", platformType = null)) }
        val prefix = mutableListOf<JsonObject>()
        var cursor: JsonObject? = null
        val interrupted = runCatching {
            source.capture(chat, "conversation", 4096, {}, { row ->
                if (prefix.size == 17) throw CancellationException("pause before next row")
                prefix += row
            }, {}, checkpoint = { cursor = it })
        }.exceptionOrNull()
        assertTrue(interrupted is CancellationException)
        assertEquals(17, prefix.size)
        database.messageDao().addMessages(MessageV2(chatId = chat, content = "inserted after first part", platformType = null))
        val tail = mutableListOf<JsonObject>()
        val coverage = mutableListOf<JsonObject>()
        source.capture(chat, "conversation", 4096, {}, { tail += it }, {}, metadata = { coverage += it }, resumeCursor = requireNotNull(cursor))
        val prefixIds = prefix.map { it.getValue("rowId") }.toSet()
        val tailIds = tail.map { it.getValue("rowId") }.toSet()
        assertEquals(23, tail.size)
        assertTrue(prefixIds.intersect(tailIds).isEmpty())
        assertEquals(40, (prefixIds + tailIds).size)
        assertFalse(tail.toString().contains("inserted after first part"))
        assertEquals(40L, coverage.single().getValue("emittedRows").jsonPrimitive.long)
        assertEquals(23L, coverage.single().getValue("emittedRowsThisPart").jsonPrimitive.long)
        assertEquals(1, (prefix + tail).map { it["tableHighWatermark"] }.distinct().size)
        val wrongChat = runCatching { source.capture(chat + 1, "conversation", 4096, {}, {}, {}, resumeCursor = cursor) }.exceptionOrNull()
        assertEquals("DIAGNOSTIC_DATABASE_CURSOR_SCOPE_CHANGED", wrongChat?.message)
    }

    @Test fun newestWorkspaceRowsAreFirstAndResumePreservesTheOrderingBoundary() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Newest first")).toInt()
        repeat(30) { index ->
            workspace.saveSynthesisCheckpoint("history-$index", chat, "run", buildJsonObject { put("history", index) })
            database.openHelper.writableDatabase.execSQL("UPDATE workspace_records SET updatedAt = ? WHERE id = ?", arrayOf<Any>(index.toLong(), "history-$index"))
        }
        var cursor: JsonObject? = null
        val prefix = mutableListOf<String>()
        runCatching {
            source.capture(chat, "workspace", 4096, {}, { row ->
                if (prefix.size == 8) throw CancellationException("next part")
                prefix += row.getValue("fields").jsonObject.getValue("id").jsonPrimitive.content
            }, {}, checkpoint = { cursor = it })
        }
        val tail = mutableListOf<String>()
        source.capture(chat, "workspace", 4096, {}, { row ->
            tail += row.getValue("fields").jsonObject.getValue("id").jsonPrimitive.content
        }, {}, resumeCursor = requireNotNull(cursor))
        assertEquals((29 downTo 0).map { "history-$it" }, prefix + tail)
        assertTrue(prefix.toSet().intersect(tail.toSet()).isEmpty())
    }

    @Test fun largeWorkspaceIncludesNewestVerdictPolicyBudgetCleanupAndRemainingWorkBeforeHistory() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Large workspace")).toInt()
        val user = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "request", platformType = null)).toInt()
        val assistant = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "partial response", platformType = null)).toInt()
        database.agentPersistenceDao().insertRun(AgentRun("latest-run", chat, user, assistant, "profile", "provider", "actual-model", status = "PARTIAL", terminalError = "GITHUB_TASK_INCOMPLETE"))
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO run_policy_snapshots (chatId, digest, schemaVersion, kind, policyRevision, taskDigest, authorityDigest, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(chat, "accepted-policy", 1, "github", "revision", "task", "authority", "{\"policy\":\"latest accepted policy\"}")
        )
        database.openHelper.writableDatabase.execSQL("INSERT INTO agent_run_policy_refs (runId, chatId, digest) VALUES (?, ?, ?)", arrayOf<Any>("latest-run", chat, "accepted-policy"))
        val history = buildJsonObject { put("historical_payload", "x".repeat(128 * 1024)) }
        repeat(144) { workspace.saveSynthesisCheckpoint("history-$it", chat, "old-run", history) }
        workspace.saveProtectedSynthesisCheckpoint("github-tool-budget:latest", chat, "latest-run", buildJsonObject { put("remaining_calls", 17) })
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:task:verification_latest",
            chat,
            "latest-run",
            buildJsonObject {
                put("verification_failure", "GITHUB_TASK_INCOMPLETE")
                put("cleanup_status", "OWNED_FIXTURE_REMOVED")
                put("remaining_requirements", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("verify exact CI head"))))
                put("token", "never-export-this-secret")
            },
            appendOnly = true
        )
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:task:verification_event_after",
            chat,
            "latest-run",
            buildJsonObject {
                put("phase", "TERMINAL_EVENT")
                put("snapshot_id", "verification_latest")
            },
            appendOnly = true
        )
        val context = RuntimeEnvironment.getApplication()
        val destination = File(context.cacheDir, "latest-large-workspace-export").apply { mkdirs() }
        try {
            val result = writeStagedChatDiagnostics(context, destination, ChatRoomV2(id = chat, title = "Large workspace"), emptyList(), ChatMcpToolConfig(), AppFeatureSettings(), "", emptyList(), source, null, false, false, fullHistory = true) {}
            ZipFile(result.file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.indexOf("latest-state.jsonl") < names.indexOf("workspace.jsonl"))
                val latest = zip.getInputStream(zip.getEntry("latest-state.jsonl")).bufferedReader().use { it.readText() }
                assertTrue(latest.contains("GITHUB_TASK_INCOMPLETE"))
                assertTrue(latest.contains("latest accepted policy"))
                assertTrue(latest.contains("remaining_calls"))
                assertTrue(latest.contains("OWNED_FIXTURE_REMOVED"))
                assertTrue(latest.contains("verify exact CI head"))
                assertFalse(latest.contains("historical_payload"))
                assertFalse(latest.contains("never-export-this-secret"))
                assertTrue(zip.getEntry("workspace.jsonl").size > 16L * 1024 * 1024)
                val first = zip.getInputStream(zip.getEntry("workspace.jsonl")).bufferedReader().use { it.readLine() }
                assertTrue(first.contains("verification_event_after"))
            }
            val workspaceReceipt = (result.manifest.getValue("stages") as JsonArray).map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == "workspace" }
            assertEquals("COMPLETED", workspaceReceipt.getValue("status").jsonPrimitive.content)
            assertEquals(result.manifest, StagedDiagnosticCapture.verify(result.file))
        } finally {
            DiagnosticCaptureStorage.deleteChat(context, chat)
            destination.deleteRecursively()
        }
    }

    @Test fun compactFailureSummaryRecoversOwnedAdmissionFailureWithoutWorkspaceOrInvocation() = runBlocking {
        val runId = "owned-admission"
        val chat = createRun(runId, "private unstructured failure")
        val timeline = """[{"type":"NOTICE","modelAuthored":false,"profileFailure":{"category":"ENGINEERING_STATE_OR_INPUT_CHANGED","phase":"engineering_guard","retryAllowed":false,"requestReplayAllowed":false}}]"""
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages_v2 SET timeline = ?, current_run_id = ? WHERE message_id = (SELECT assistant_message_id FROM agent_runs WHERE run_id = ?)",
            arrayOf<Any>(timeline, runId, runId)
        )
        val summary = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals(JsonPrimitive("ENGINEERING_STATE_OR_INPUT_CHANGED"), summary["failure_code"])
        assertEquals(JsonPrimitive("engineering_guard"), summary["failed_stage"])
        assertEquals(JsonPrimitive(false), summary["request_replay_allowed"])
        assertEquals(JsonPrimitive("MESSAGE_PROFILE_FAILURE"), summary.getValue("field_sources").jsonObject["failure_code"])
        assertEquals(JsonNull, summary["tested_sha"])
        assertEquals(JsonNull, summary["invocation_id"])
        assertFalse(summary.containsKey("dispatched"))
        assertFalse(summary.toString().contains("private unstructured"))
    }

    @Test fun compactFailureSummaryDoesNotBorrowMessageFromAnotherRunOrOwnershipFence() = runBlocking {
        val runId = "owned-fence"
        val chat = createRun(runId, null)
        val timeline = """[{"type":"NOTICE","profileFailure":{"category":"REQUEST_REJECTED","phase":"provider_admission"}}]"""
        for ((messageRun, fence) in listOf("another-run" to 0L, runId to 1L)) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE messages_v2 SET timeline = ?, current_run_id = ?, ownership_fence = ? WHERE message_id = (SELECT assistant_message_id FROM agent_runs WHERE run_id = ?)",
                arrayOf<Any>(timeline, messageRun, fence, runId)
            )
            val summary = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
            assertEquals(JsonNull, summary["failure_code"])
            assertTrue(summary.getValue("source_record_ids").jsonArray.isEmpty())
        }
    }

    @Test fun compactFailureSummaryBoundsOwnedTimelineWithoutClaimingFullCaptureOmission() = runBlocking {
        val runId = "oversized-message"
        val chat = createRun(runId, null)
        val timeline = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "NOTICE")
                    put("text", "漢".repeat(100_000))
                    put("profileFailure", buildJsonObject { put("category", "REQUEST_REJECTED") })
                }
            )
        ).toString()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages_v2 SET timeline = ?, current_run_id = ? WHERE message_id = (SELECT assistant_message_id FROM agent_runs WHERE run_id = ?)",
            arrayOf<Any>(timeline, runId, runId)
        )
        val omissions = mutableListOf<String>()
        val summary = source.latestFailureSummary(chat, "test", 141, "build", {}, omissions::add)
        assertEquals(JsonNull, summary["failure_code"])
        assertEquals(JsonPrimitive("MESSAGE_TIMELINE_BYTE_LIMIT"), summary.getValue("projection_limitations").jsonArray.single().jsonObject["reason"])
        assertTrue(omissions.isEmpty())
        assertTrue(summary.toString().length < 4096)
        assertFalse(summary.toString().contains("漢"))
    }

    @Test fun compactFailureSummaryLeadsWithBoundedCausalEvidenceAndExactBuildBinding() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Failure summary")).toInt()
        val user = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "request", platformType = null)).toInt()
        val assistant = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "partial response", platformType = null)).toInt()
        database.agentPersistenceDao().insertRun(AgentRun("run-summary", chat, user, assistant, "profile", "provider", "model", status = "PARTIAL"))
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:repo-binding:verification_current_0123456789abcdef01234567",
            chat,
            "run-summary",
            buildJsonObject {
                put("task_state", "PARTIAL")
                put("task_outcome", "PARTIAL")
                put("stop_code", "GITHUB_EVIDENCE_MISSING")
                put("failure_code", "GITHUB_EXTRACTION_OUTPUT_LIMIT")
                put("invocation_id", "claim-batch-3")
                put("failed_stage", "claim_extraction")
                put("recovery_action", "RESUME_SAVED_BATCH_WITH_SMALLER_CLAIM_LIMIT")
                put("tested_sha", "a".repeat(40))
                put(
                    "acceptance_ledger",
                    buildJsonObject {
                        put(
                            "requirements",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("id", "verify-current-ci")
                                        put("requirement", "Refresh the PR check for the pinned head SHA")
                                        put("acceptance_state", "BLOCKED")
                                        put("missing_evidence_ids", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("receipt-ci-7"))))
                                    }
                                )
                            )
                        )
                    }
                )
            },
            appendOnly = true
        )

        val summary = source.latestFailureSummary(chat, "0.9.test", 999, "build-sha", {}, {})
        assertEquals("run-summary", summary.getValue("run_id").jsonPrimitive.content)
        assertEquals("0.9.test", summary.getValue("app_version").jsonPrimitive.content)
        assertEquals(999, summary.getValue("app_build").jsonPrimitive.int)
        assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", summary.getValue("latest_verdict").jsonPrimitive.content)
        assertEquals("Refresh the PR check for the pinned head SHA", summary.getValue("failed_criterion").jsonPrimitive.content)
        assertEquals("receipt-ci-7", summary.getValue("causal_receipt_ids").jsonArray.single().jsonPrimitive.content)
        assertEquals("claim-batch-3", summary.getValue("invocation_id").jsonPrimitive.content)
        assertEquals("a".repeat(40), summary.getValue("tested_sha").jsonPrimitive.content)
    }

    @Test fun protectedEvidenceIsRestoredWithinBudgetInsteadOfExportingOnlyChunkReferences() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:one",
            chat,
            "run",
            buildJsonObject {
                put("report", "Retained verified tool receipt")
                put("token", "must-never-export")
            },
            appendOnly = true
        )
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "workspace", 4096, {}, { rows += it }, omissions::add)
        assertTrue(rows.toString().contains("Retained verified tool receipt"))
        assertFalse(rows.toString().contains("must-never-export"))
        assertFalse(rows.toString().contains("workspace-memory-"))
        assertTrue(omissions.isEmpty())
    }

    @Test fun oversizedVerificationUsesMatchingCompactEventAndOutputWithoutDroppingTheFailure() = runBlocking {
        val chat = createRun("large-summary", "A job is active · code=conversation_conflict · dispatch=not_dispatched")
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:binding:verification_current_snapshot",
            chat,
            "large-summary",
            buildJsonObject {
                put("failure_code", "conversation_conflict")
                put("large_checklist", "x".repeat(364_489))
            }
        )
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:binding:verification_event_after",
            chat,
            "large-summary",
            buildJsonObject {
                put("snapshot_id", "verification_current_snapshot")
                put("task_state", "BLOCKED")
                put("task_outcome", "PARTIAL")
                put("failure_code", "conversation_conflict")
                put("phase", "INTERRUPTED")
            },
            appendOnly = true
        )
        workspace.saveProtectedSynthesisCheckpoint(
            "github-output:binding",
            chat,
            "large-summary",
            buildJsonObject {
                put(
                    "failure_details",
                    buildJsonObject {
                        put("code", "conversation_conflict")
                        put("invocation_id", "rejected-send")
                        put("failed_stage", "provider_admission")
                        put("dispatch_state", "not_dispatched")
                    }
                )
            }
        )
        val omissions = mutableListOf<String>()
        val summary = source.latestFailureSummary(chat, "test", 141, "app-commit", {}, omissions::add)
        assertEquals("conversation_conflict", summary.getValue("failure_code").jsonPrimitive.content)
        assertEquals("BLOCKED", summary.getValue("task_state").jsonPrimitive.content)
        assertEquals("PARTIAL", summary.getValue("task_outcome").jsonPrimitive.content)
        assertEquals("provider_admission", summary.getValue("failed_stage").jsonPrimitive.content)
        assertEquals("rejected-send", summary.getValue("invocation_id").jsonPrimitive.content)
        assertEquals("RECONCILE_ACTIVE_GATEWAY_JOB", summary.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("VERIFICATION_EVENT", summary.getValue("field_sources").jsonObject.getValue("failure_code").jsonPrimitive.content)
        assertEquals("FAILURE_CODE_POLICY", summary.getValue("field_sources").jsonObject.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("PROTECTED_PAYLOAD_BYTE_LIMIT", summary.getValue("projection_limitations").jsonArray.single().jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(omissions.isEmpty())
        assertTrue(summary.toString().length < 4096)

        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:binding:verification_event_unrelated",
            chat,
            "large-summary",
            buildJsonObject {
                put("snapshot_id", "verification_current_unrelated")
                put("task_state", "VERIFIED")
                put("failure_code", "GITHUB_UNRELATED_FAILURE")
            },
            appendOnly = true
        )
        val mismatched = source.latestFailureSummary(chat, "test", 141, "app-commit", {}, omissions::add)
        assertEquals("conversation_conflict", mismatched.getValue("failure_code").jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, mismatched["task_state"])
        assertFalse(mismatched.toString().contains("GITHUB_UNRELATED_FAILURE"))
        assertTrue(mismatched.getValue("projection_limitations").jsonArray.any { it.jsonObject["reason"] == JsonPrimitive("LATEST_FAILURE_SNAPSHOT_MISMATCH") })
    }

    @Test fun admissionFailureCannotBorrowAnotherRunsVerdictAndRetainsItsTypedRecovery() = runBlocking {
        val chat = createRun("prior", null)
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:old:verification_current_snapshot",
            chat,
            "prior",
            buildJsonObject {
                put("failure_code", "GITHUB_UNRELATED_FAILURE")
                put("tested_sha", "f".repeat(40))
            }
        )
        val user = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "new request", platformType = null)).toInt()
        val assistant = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "", platformType = null)).toInt()
        database.agentPersistenceDao().insertRun(AgentRun("current", chat, user, assistant, "profile", "provider", "model", status = "FAILED", terminalError = "The selected profile could not be verified (EXECUTION_SELECTION_INVALID; provider_admission)."))
        val summary = source.latestFailureSummary(chat, "test", 141, "build-commit", {}, {})
        assertEquals("current", summary.getValue("run_id").jsonPrimitive.content)
        assertEquals("EXECUTION_SELECTION_INVALID", summary.getValue("failure_code").jsonPrimitive.content)
        assertEquals("provider_admission", summary.getValue("failed_stage").jsonPrimitive.content)
        assertEquals("REFRESH_SELECTED_PROFILE_AND_GATEWAY_READINESS", summary.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("TERMINAL_ERROR", summary.getValue("field_sources").jsonObject.getValue("failure_code").jsonPrimitive.content)
        assertEquals("NOT_RETAINED", summary.getValue("tested_sha_status").jsonPrimitive.content)
        assertTrue(summary.getValue("source_record_ids").jsonArray.isEmpty())
    }

    @Test fun exactBoundActiveReviewFailureOverridesSuccessfulOutputWithoutBorrowingAnotherTask() = runBlocking {
        val runId = "review-run"
        val chat = createRun(runId, null)
        val verificationId = "github-evidence:bound-task:verification_current_snapshot"
        val reviewId = "github-review:bound-task:active"
        workspace.saveProtectedSynthesisCheckpoint(
            verificationId,
            chat,
            runId,
            buildJsonObject {
                put("completion_verified", false)
                put("head_sha", "a".repeat(40))
            }
        )
        workspace.saveProtectedSynthesisCheckpoint(
            "github-output:bound-task",
            chat,
            runId,
            buildJsonObject {
                put("state", "COMPLETED")
                put("invocation_id", "successful-primary")
            }
        )
        workspace.saveProtectedSynthesisCheckpoint(reviewId, chat, runId, activeReviewFailure("review-attempt"))
        workspace.saveProtectedSynthesisCheckpoint("github-review:another-task:active", chat, runId, activeReviewFailure("unrelated-review"))

        val summary = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals(JsonPrimitive("review-attempt"), summary["invocation_id"])
        assertEquals(JsonPrimitive("provider_admission"), summary["failed_stage"])
        assertEquals(JsonPrimitive("EXECUTION_SELECTION_INVALID"), summary["failure_code"])
        assertEquals(JsonPrimitive("REVIEW_CHECKPOINT"), summary.getValue("field_sources").jsonObject["invocation_id"])
        assertEquals(JsonPrimitive(reviewId), summary.getValue("source_record_ids").jsonArray.first())
        assertEquals(JsonNull, summary["tested_sha"])
        assertEquals(JsonPrimitive("a".repeat(40)), summary["observed_head_sha"])
        assertFalse(summary.toString().contains("unrelated-review"))

        workspace.saveProtectedSynthesisCheckpoint(reviewId, chat, runId, activeReviewFailure(null))
        val withoutInvocation = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals(JsonNull, withoutInvocation["invocation_id"])
        assertEquals(JsonPrimitive("EXECUTION_SELECTION_INVALID"), withoutInvocation["failure_code"])

        workspace.saveProtectedSynthesisCheckpoint(
            verificationId,
            chat,
            runId,
            buildJsonObject {
                put("completion_verified", true)
                put("head_sha", "a".repeat(40))
                put("tested_sha", "b".repeat(40))
            }
        )
        val verified = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals(JsonPrimitive("VERIFIED"), verified["latest_verdict"])
        assertEquals(JsonNull, verified["failure_code"])
        assertEquals(JsonPrimitive("b".repeat(40)), verified["tested_sha"])
        assertEquals(JsonPrimitive("a".repeat(40)), verified["observed_head_sha"])
        assertEquals(listOf(JsonPrimitive(verificationId)), verified.getValue("source_record_ids").jsonArray.toList())
    }

    @Test fun resolvedReviewFailureFieldsAreHistoricalAndDoNotOverrideCurrentEvidence() = runBlocking {
        val chat = createRun("resolved-review", null)
        workspace.saveProtectedSynthesisCheckpoint("github-evidence:binding:verification_current_snapshot", chat, "resolved-review", buildJsonObject { put("completion_verified", false) })
        workspace.saveProtectedSynthesisCheckpoint(
            "github-output:binding",
            chat,
            "resolved-review",
            buildJsonObject {
                put("state", "COMPLETED")
                put("invocation_id", "successful-primary")
            }
        )
        for (state in listOf("REVIEWED", "COMPLETED")) {
            workspace.saveProtectedSynthesisCheckpoint(
                "github-review:binding:active",
                chat,
                "resolved-review",
                JsonObject(activeReviewFailure("historical-review") + ("state" to JsonPrimitive(state)))
            )
            val summary = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
            assertEquals(JsonNull, summary["failure_code"])
            assertEquals(JsonPrimitive("successful-primary"), summary["invocation_id"])
            assertFalse(summary.getValue("source_record_ids").jsonArray.contains(JsonPrimitive("github-review:binding:active")))
        }
    }

    @Test fun unboundOrEarlierRunReviewCannotSupplyFailureProvenance() = runBlocking {
        val chat = createRun("earlier-review", null)
        workspace.saveProtectedSynthesisCheckpoint("github-review:binding:active", chat, "earlier-review", activeReviewFailure("earlier-run-review"))
        // Without a verification binding, even the same-run review is not joined by recency.
        val unbound = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals(JsonNull, unbound["failure_code"])
        assertEquals(JsonNull, unbound["invocation_id"])

        val user = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "new request", platformType = null)).toInt()
        val assistant = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "", platformType = null)).toInt()
        database.agentPersistenceDao().insertRun(AgentRun("current-review", chat, user, assistant, "profile", "provider", "model", status = "FAILED"))
        workspace.saveProtectedSynthesisCheckpoint("github-evidence:binding:verification_current_snapshot", chat, "current-review", buildJsonObject { put("completion_verified", false) })
        val boundToAnotherRun = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals(JsonPrimitive("current-review"), boundToAnotherRun["run_id"])
        assertEquals(JsonNull, boundToAnotherRun["failure_code"])
        assertEquals(JsonNull, boundToAnotherRun["invocation_id"])
        assertFalse(boundToAnotherRun.toString().contains("earlier-run-review"))
    }

    private fun activeReviewFailure(invocationId: String?) = buildJsonObject {
        put("state", "BLOCKED")
        put("generation_state", "COMPLETED")
        put("review_state", "FAILED")
        put("delivery_state", "WITHHELD")
        put("failure_code", "EXECUTION_SELECTION_INVALID")
        put("failed_stage", "provider_admission")
        put("review_stage", "answer_review")
        invocationId?.let { put("invocation_id", it) }
    }

    @Test fun plainCheckpointAndWorkspacePolicyRemainDiscoverableWithoutLegacyPolicyRows() = runBlocking {
        val chat = createRun("plain-run", null)
        workspace.saveSynthesisCheckpoint(
            "github-evidence:plain:verification_current_snapshot",
            chat,
            "plain-run",
            buildJsonObject { put("failure_code", "GITHUB_EVIDENCE_MISSING") }
        )
        workspace.saveProtectedSynthesisCheckpoint(
            "github-run-policy:plain-run:digest",
            chat,
            "plain-run",
            buildJsonObject { put("schema", "github.run-policy.v1") },
            appendOnly = true
        )
        val summary = source.latestFailureSummary(chat, "test", 141, "build", {}, {})
        assertEquals("GITHUB_EVIDENCE_MISSING", summary.getValue("failure_code").jsonPrimitive.content)
        val references = source.latestPolicyReferences(chat) {}
        assertEquals("github-run-policy:plain-run:digest", references.getValue("references").jsonArray.single().jsonObject.getValue("record_id").jsonPrimitive.content)
        val latest = mutableListOf<JsonObject>()
        source.capture(chat, "latest_state", 4096, {}, { latest += it }, {})
        assertTrue(latest.any { it["fields"]?.jsonObject?.get("id") == JsonPrimitive("github-run-policy:plain-run:digest") })
    }

    private suspend fun createRun(id: String, terminalError: String?): Int {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = id)).toInt()
        val user = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "request", platformType = null)).toInt()
        val assistant = database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "", platformType = null)).toInt()
        database.agentPersistenceDao().insertRun(AgentRun(id, chat, user, assistant, "profile", "provider", "model", status = "FAILED", terminalError = terminalError))
        return chat
    }

    @Test fun sourceBudgetRetainsOversizedAssistantForRedactedChunking() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        val content = "x".repeat(147_734)
        database.messageDao().addMessages(MessageV2(chatId = chat, content = content, platformType = null))
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "conversation", 98_304, {}, { rows += it }, omissions::add, maximumSourceBytes = 256 * 1024)
        assertEquals(1, rows.size)
        assertTrue(rows.single().toString().contains(content))
        assertTrue(omissions.isEmpty())
    }

    @Test fun allReceiptsBeyondOneHundredHaveExplicitCoverageAndProjectedTimeline() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        val messages = listOf(
            database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "request", platformType = null)),
            database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "response", platformType = null))
        )
        val run = AgentRun("run", chat, messages[0].toInt(), messages[1].toInt(), "profile", "provider", "model", status = "PARTIAL")
        database.agentPersistenceDao().insertRun(run)
        repeat(153) { index ->
            database.agentPersistenceDao().insertToolEvent(ToolEvent("event-$index", "run", index, "call-$index", null, null, "github_context", "github_context", "{}", "{}", "JSON", "COMPLETED"))
        }
        // A metadata-only run index must remain available even when full run snapshots are large.
        database.openHelper.writableDatabase.execSQL("UPDATE agent_runs SET synthesis_snapshot = ? WHERE run_id = ?", arrayOf("x".repeat(200_000), "run"))
        val rows = mutableListOf<JsonObject>()
        val coverage = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "tool_receipts", 4096, {}, { rows += it }, omissions::add, metadata = { coverage += it })
        assertEquals(153, rows.size)
        assertEquals(153L, coverage.single()["expectedRows"]!!.jsonPrimitive.long)
        assertEquals(153L, coverage.single()["emittedRows"]!!.jsonPrimitive.long)
        assertEquals("null", coverage.single()["rowLimit"].toString())
        val runs = mutableListOf<JsonObject>()
        source.capture(chat, "run_index", 1024, {}, { runs += it }, omissions::add)
        assertEquals(1, runs.size)
        assertFalse(runs.toString().contains("synthesis_snapshot"))
        val timeline = mutableListOf<JsonObject>()
        source.capture(chat, "timeline", 4096, {}, { timeline += requireNotNull(DiagnosticTimeline.database(it, 123)) }, omissions::add)
        assertEquals(154, timeline.size)
        assertTrue(timeline.any { it["eventId"]?.jsonPrimitive?.content == "tool_events:event-152" })
        assertTrue(omissions.isEmpty())
        val context = RuntimeEnvironment.getApplication()
        val destination = File(context.cacheDir, "all-receipt-export").apply { mkdirs() }
        try {
            val exported = writeStagedChatDiagnostics(context, destination, ChatRoomV2(id = chat, title = "Selected"), emptyList(), ChatMcpToolConfig(), AppFeatureSettings(), "", emptyList(), source, null, false, false, fullHistory = true) {}
            val exportedRows = ZipFile(exported.file).use { zip ->
                zip.getInputStream(zip.getEntry("tool-receipts.jsonl")).bufferedReader().use { reader ->
                    reader.lineSequence().map { Json.parseToJsonElement(it).jsonObject }.filter { it["fields"] is JsonObject }.toList()
                }
            }
            assertEquals(153, exportedRows.size)
            assertEquals(153, exportedRows.map { it.getValue("fields").jsonObject.getValue("event_id") }.distinct().size)
            assertEquals(exported.manifest, StagedDiagnosticCapture.verify(exported.file))
        } finally {
            DiagnosticCaptureStorage.deleteChat(context, chat)
            destination.deleteRecursively()
        }
    }

    @Test fun roomBackedAdapterPublishesVerifiableOversizedAndEscapedMessageChunks() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Chunk verification")).toInt()
        val content = "x".repeat(147_734)
        val escaped = "\"\\\u0001😀\n".repeat(30_000)
        database.messageDao().addMessages(
            MessageV2(chatId = chat, content = content, platformType = null),
            MessageV2(chatId = chat, content = escaped, platformType = null),
            MessageV2(chatId = chat, content = "x".repeat(1_048_577), platformType = null)
        )
        val context = RuntimeEnvironment.getApplication()
        val destination = File(context.cacheDir, "large-message-export").apply { mkdirs() }
        try {
            val result = writeStagedChatDiagnostics(context, destination, ChatRoomV2(id = chat, title = "Chunk verification"), emptyList(), ChatMcpToolConfig(), AppFeatureSettings(), "", emptyList(), source, null, false, false, fullHistory = true) {}
            val lines = ZipFile(result.file).use { zip ->
                zip.getInputStream(zip.getEntry("conversation.jsonl")).bufferedReader().use { it.readLines() }
            }
            assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 512 * 1024 })
            val chunks = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["kind"]?.jsonPrimitive?.content == "diagnostic_record_chunk" }
            val contents = chunks.groupBy { it.getValue("recordId").jsonPrimitive.content }.values.map { group ->
                val ordered = group.sortedBy { it.getValue("chunkIndex").jsonPrimitive.int }
                assertEquals(ordered.size, ordered.first().getValue("chunkCount").jsonPrimitive.int)
                ordered.forEachIndexed { index, chunk ->
                    assertEquals(index, chunk.getValue("chunkIndex").jsonPrimitive.int)
                    assertEquals(DiagnosticRecordChunks.sha256(chunk.getValue("content").jsonPrimitive.content), chunk.getValue("chunkSha256").jsonPrimitive.content)
                }
                val reassembled = ordered.joinToString("") { it.getValue("content").jsonPrimitive.content }
                assertEquals(DiagnosticRecordChunks.sha256(reassembled), ordered.first().getValue("recordSha256").jsonPrimitive.content)
                Json.parseToJsonElement(reassembled).jsonObject.getValue("fields").jsonObject.getValue("content").jsonPrimitive.content
            }
            assertEquals(setOf(content, escaped), contents.toSet())
            val receipt = (result.manifest.getValue("stages") as JsonArray).map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == "conversation" }
            assertEquals("PARTIAL", receipt.getValue("status").jsonPrimitive.content)
            assertEquals(1L, receipt.getValue("omissions").jsonObject.getValue("RECORD_BYTE_LIMIT").jsonPrimitive.long)
            assertFalse(result.complete)
        } finally {
            DiagnosticCaptureStorage.deleteChat(context, chat)
            destination.deleteRecursively()
        }
    }
}
