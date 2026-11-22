package dev.chungjungsoo.gptmobile.data.workspace

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubAuthorizedCatalog
import dev.chungjungsoo.gptmobile.data.github.GitHubDecisionMode
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProtectedSynthesisCheckpointTest {
    private lateinit var database: ChatDatabaseV2
    private val secrets = mutableMapOf<String, ByteArray>()
    private var beforePut: (suspend () -> Unit)? = null
    private val vault = object : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            require(secret.size <= 65_536)
            beforePut?.let { action ->
                beforePut = null
                action()
            }
            secrets[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = secrets[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            secrets.remove(secretRef)
        }
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
    }

    @After fun close() {
        database.close()
    }

    @Test fun fixtureApprovalClassificationAndRestartPreserveOriginalEncryptedApproval() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Fixture approval")).toInt()
        val store = WorkspaceRepository(database, vault)
        val binding = "b".repeat(64)
        val prefix = "github-evidence:$binding:"
        val tools = listOf(
            AgentToolDefinition("github_context", "Read context", kotlinx.serialization.json.Json.parseToJsonElement("""{"properties":{"action":{"enum":["status"]}}}""") as JsonObject),
            AgentToolDefinition(GitHubAuthorizedCatalog.TOOL, "Discover tools", JsonObject(emptyMap()))
        )
        fun book(mode: GitHubDecisionMode?, revision: String?) = GitHubEvidenceBook(
            binding, "owner/repo", "main", tools.map { it.name }.toSet(),
            fixtureDecisionMode = mode,
            authorizationRevision = revision,
            runtimeIdentity = { JsonObject(emptyMap()) },
            load = { store.protectedSynthesisCheckpoint(prefix + it) },
            save = { id, value -> store.saveProtectedSynthesisCheckpoint(prefix + id, chat, "run", value, appendOnly = !GitHubEvidenceBook.currentSnapshot(id)) }
        )
        val original = book(null, null).fixtureApprovalPreflight()
        val accepted = book(GitHubDecisionMode.ASK, "approved-task")
        val approval = accepted.fixtureApprovalPreflight()
        val catalog = buildJsonObject {
            put("receipt_id", "catalog")
            put("binding", binding)
            put("repository", "owner/repo")
            put("tool", GitHubAuthorizedCatalog.TOOL)
            put("trusted", true)
            put("success", true)
            put(
                "result",
                buildJsonObject {
                    put("inventory_version", "catalog-v1")
                    put("authorized_tool_count", tools.size)
                    put("inventory_offset", 0)
                    put("returned", tools.size)
                    put("has_more", false)
                    put(
                        "authorized_inventory",
                        JsonArray(
                            tools.map { tool ->
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("schema_fingerprint", GitHubEvidenceBook.digest(tool.inputSchema.toString()))
                                }
                            }
                        )
                    )
                }
            )
        }
        val classified = accepted.classifyBeforeAcquisition("Test all authorized GitHub integration tools. Classify each capability before bulk execution. [github-workflow-preset:test_integration]", tools, mapOf("catalog" to catalog), approval)
        assertTrue(classified.containsKey("capability_classification"))
        assertEquals(JsonPrimitive("ASK"), classified["decision_mode"])
        assertEquals(classified, book(GitHubDecisionMode.AUTO_APPROVE, "different-revision").fixtureApprovalPreflight())
        val suffix = GitHubEvidenceBook.digest(binding).take(24)
        assertEquals(original, store.protectedSynthesisCheckpoint(prefix + "fixture_preflight_$suffix"))
        assertEquals(approval, store.protectedSynthesisCheckpoint(prefix + "fixture_preflight_known_$suffix"))
        assertEquals("github_evidence", store.dao.get(prefix + "fixture_preflight_$suffix")?.kind)
        assertEquals("synthesis_checkpoint", store.dao.get(prefix + "fixture_classification_current_$suffix")?.kind)
    }

    @Test fun conflictingImmutableWriteReportsApplicationFailureAndRetainsOriginalChunks() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Immutable evidence")).toInt()
        val store = WorkspaceRepository(database, vault)
        val original = buildJsonObject { put("private_source", "First saved evidence") }
        val changed = buildJsonObject { put("private_source", "Changed evidence") }
        store.saveProtectedSynthesisCheckpoint("private-record-identity", chat, "run", original, appendOnly = true)
        val references = secrets.keys.toSet()
        val failure = runCatching { store.saveProtectedSynthesisCheckpoint("private-record-identity", chat, "run", changed, appendOnly = true) }.exceptionOrNull() as WorkspaceEvidenceConflict
        assertEquals("WORKSPACE_EVIDENCE_CONFLICT", failure.event().errorCode)
        assertEquals(JsonPrimitive("application_persistence"), failure.event().failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), failure.event().failureDetails?.get("retryable"))
        assertFalse(failure.event().failureDetails.toString().contains("private-record-identity"))
        assertFalse(failure.event().failureDetails.toString().contains("Changed evidence"))
        assertEquals(original, store.protectedSynthesisCheckpoint("private-record-identity"))
        assertEquals(references, secrets.keys)
    }

    @Test fun identicalConcurrentAppendKeepsFirstChunksAndDiscardsUnpublishedDuplicate() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Concurrent evidence")).toInt()
        val store = WorkspaceRepository(database, vault)
        val payload = buildJsonObject { put("decision", "ASK") }
        beforePut = { assertTrue(store.saveProtectedSynthesisCheckpoint("approval", chat, "first-run", payload, appendOnly = true)) }
        assertTrue(store.saveProtectedSynthesisCheckpoint("approval", chat, "second-run", payload, appendOnly = true))
        assertEquals(payload, store.protectedSynthesisCheckpoint("approval"))
        assertEquals("first-run", store.dao.get("approval")?.runId)
        assertEquals(1, secrets.size)
    }

    @Test fun reopenedReceiptsCountOneContractAcrossMultipleAttempts() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Task progress")).toInt()
        val store = WorkspaceRepository(database, vault)
        val binding = "b".repeat(64)
        val key = "github-evidence:$binding:"
        store.saveProtectedSynthesisCheckpoint(key + "task_contract", chat, "first-run", buildJsonObject { put("binding", binding) }, appendOnly = true)
        val current = key + "verification_current_" + dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(binding).take(24)
        store.saveProtectedSynthesisCheckpoint(
            current,
            chat,
            "first-run",
            buildJsonObject {
                put("binding", binding)
                put("phase", "IN_PROGRESS")
            }
        )
        store.saveProtectedSynthesisCheckpoint(
            current,
            chat,
            "next-run",
            buildJsonObject {
                put("binding", binding)
                put("task_state", "PARTIAL")
            }
        )
        val restored = WorkspaceRepository(database, vault).conversationWork(chat)
        assertEquals(1, restored.tasks.size)
        assertEquals("PARTIAL", restored.tasks.single().state)
        assertFalse(restored.tasks.single().completed)
        assertFalse(restored.olderTasks)
        assertTrue(store.conversationWork(chat + 1).tasks.isEmpty())
    }

    @Test fun outcomeSearchOpensTheRetainedTurnAndAvoidsNewerUnrelatedAnswers() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Builds")).toInt()
        val dao = database.agentPersistenceDao()
        val user = dao.insertMessage(MessageV2(chatId = chat, content = "Build this", platformType = null)).toInt()
        val answer = dao.insertMessage(MessageV2(chatId = chat, content = "Built", platformType = "p", currentRunId = "first")).toInt()
        fun attempt(id: String, at: Long) = AgentRun(id, chat, user, answer, "p", "provider", "model", status = "COMPLETED", createdAt = at, displayRole = "DIRECT_REPLY")
        database.agentRunDao().upsert(attempt("first", 1))
        assertEquals(answer, database.agentRunDao().chatsWithRecordedOutcome("COMPLETED").single().messageId)
        database.agentRunDao().upsert(attempt("second", 2))
        assertEquals(user, database.agentRunDao().chatsWithRecordedOutcome("COMPLETED").single().messageId)
        val store = WorkspaceRepository(database, vault)
        store.saveProtectedSynthesisCheckpoint(
            "engineering:scope:decision:1",
            chat,
            "scope",
            buildJsonObject {
                put("kind", "decision")
                put("confidence", "USER_DECISION_NOT_EXECUTION_AUTHORITY")
                put("source_message_id", user)
            },
            engineeringMemory = true
        )
        assertEquals(mapOf(chat to user), store.chatsByOutcome("decision", setOf(chat)))
        assertTrue(store.chatsByOutcome("build", setOf(chat)).isEmpty())
        assertTrue(database.agentRunDao().chatsWithRecordedOutcome("INTERRUPTED").isEmpty())
    }

    @Test fun conditionalRecoveryCheckpointCannotReplaceChangedEvidenceOrASupersededOwner() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Recovery")).toInt()
        val user = database.agentPersistenceDao().insertMessage(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(chatId = chat, content = "Task", platformType = null)).toInt()
        val answer = database.agentPersistenceDao().insertMessage(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(chatId = chat, content = "", platformType = "profile", currentRunId = "run", ownershipFence = 3)).toInt()
        database.agentRunDao().upsert(dev.chungjungsoo.gptmobile.data.database.entity.AgentRun("run", chat, user, answer, "profile", "CUSTOM", "model", "RUNNING", ownershipFence = 3))
        val repository = WorkspaceRepository(database, vault)
        val first = buildJsonObject { put("state", "WAITING_PROVIDER_LIMIT") }
        val newer = buildJsonObject { put("state", "COMPLETED") }
        val blocked = buildJsonObject { put("state", "BLOCKED_RECOVERY") }
        repository.saveProtectedSynthesisCheckpoint("boundary", chat, "run", first)
        val firstDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(first.toString())
        repository.saveProtectedSynthesisCheckpoint("boundary", chat, "run", newer)
        assertFalse(repository.saveProtectedSynthesisCheckpoint("boundary", chat, "run", blocked, expectedDigest = firstDigest, expectedRunFence = 3))
        assertEquals(newer, repository.protectedSynthesisCheckpoint("boundary"))
        val currentDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(newer.toString())
        assertFalse(repository.saveProtectedSynthesisCheckpoint("boundary", chat, "run", blocked, expectedDigest = currentDigest, expectedRunFence = 2))
        assertEquals(newer, repository.protectedSynthesisCheckpoint("boundary"))
        assertTrue(repository.saveProtectedSynthesisCheckpoint("boundary", chat, "run", blocked, expectedDigest = currentDigest, expectedRunFence = 3))
        assertEquals(blocked, repository.protectedSynthesisCheckpoint("boundary"))
        assertEquals(1, secrets.size)
    }

    @Test fun largeUnicodeReportSurvivesReopenWithoutPlaintextInMetadata() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val report = "Private 🌍 evidence ".repeat(5000)
        val payload = buildJsonObject { put("report", report) }
        WorkspaceRepository(database, vault).saveProtectedSynthesisCheckpoint("pass", chat, "run", payload)
        assertTrue(secrets.size > 1)
        assertTrue(secrets.values.all { it.size <= 65_536 })
        assertFalse(database.workspaceDao().get("pass")!!.payload.contains("Private"))
        assertEquals(payload, WorkspaceRepository(database, vault).protectedSynthesisCheckpoint("pass"))
    }

    @Test fun missingOrCorruptChunksAreNeverReturnedAsCompletedEvidence() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val repository = WorkspaceRepository(database, vault)
        repository.saveProtectedSynthesisCheckpoint("pass", chat, "run", buildJsonObject { put("report", "Private evidence") })
        val key = secrets.keys.single()
        secrets[key] = "altered".encodeToByteArray()
        assertNull(repository.protectedSynthesisCheckpoint("pass"))
        secrets.clear()
        assertNull(repository.protectedSynthesisCheckpoint("pass"))
    }

    @Test fun replacingCheckpointReleasesPriorChunksAndTemporaryChatsStoreNothing() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val temporary = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Temporary", isTemporary = true)).toInt()
        val repository = WorkspaceRepository(database, vault)
        repository.saveProtectedSynthesisCheckpoint("pass", chat, "run", buildJsonObject { put("report", "First") })
        val previous = secrets.keys.toSet()
        repository.saveProtectedSynthesisCheckpoint("pass", chat, "run", buildJsonObject { put("report", "Second") })
        assertTrue(secrets.keys.intersect(previous).isEmpty())
        assertEquals(JsonPrimitive("Second"), repository.protectedSynthesisCheckpoint("pass")?.get("report"))
        val count = secrets.size
        repository.saveProtectedSynthesisCheckpoint("private", temporary, "run", buildJsonObject { put("report", "Do not retain") })
        assertNull(database.workspaceDao().get("private"))
        assertEquals(count, secrets.size)
    }

    private suspend fun terminalOwner(chatId: Int): AgentRun {
        database.messageDao().addMessages(
            MessageV2(id = 1, chatId = chatId, content = "Task", platformType = null),
            MessageV2(id = 2, chatId = chatId, content = "", platformType = "p", linkedMessageId = 1, currentRunId = "run", ownershipFence = 7)
        )
        return AgentRun("run", chatId, 1, 2, "p", "CUSTOM", "model", "FAILED", startedAt = 5, ownershipFence = 7).also { database.agentRunDao().upsert(it) }
    }

    @Test fun terminalCheckpointCompareAndSetRequiresTheExactBytesAndCurrentOwner() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val run = terminalOwner(chat)
        val repository = WorkspaceRepository(database, vault)
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
            put("draft", "Retained")
        }
        val terminal = buildJsonObject {
            put("state", "FAILED")
            put("resume_allowed", false)
            put("draft", "Retained")
        }
        repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, saved)
        val originalRefs = secrets.keys.toSet()
        val digest = WorkspaceRepository.digest(saved.toString())
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 8, digest, "FAILED", terminal))
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, "0".repeat(64), "FAILED", terminal))
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, digest, "CANCELED", terminal))
        assertEquals(originalRefs, secrets.keys)
        assertEquals(saved, repository.protectedSynthesisCheckpoint("pass"))
        assertTrue(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, digest, "FAILED", terminal))
        assertEquals(terminal, repository.protectedSynthesisCheckpoint("pass"))
        assertTrue(secrets.keys.intersect(originalRefs).isEmpty())
    }

    @Test fun checkpointChangedWhileEncryptingIsRetainedAndUnpublishedChunksAreRemoved() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val run = terminalOwner(chat)
        val repository = WorkspaceRepository(database, vault)
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
        }
        val newer = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
            put("draft", "New evidence")
        }
        repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, saved)
        beforePut = { repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, newer) }
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, WorkspaceRepository.digest(saved.toString()), "FAILED", buildJsonObject { put("state", "FAILED") }))
        assertEquals(newer, repository.protectedSynthesisCheckpoint("pass"))
        assertEquals(1, secrets.size)
    }

    @Test fun supersededOwnerCannotTerminalizeAStillReadableCheckpoint() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Checkpoint")).toInt()
        val run = terminalOwner(chat)
        val repository = WorkspaceRepository(database, vault)
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
        }
        repository.saveProtectedSynthesisCheckpoint("pass", chat, run.runId, saved)
        beforePut = {
            val message = requireNotNull(database.agentPersistenceDao().rawRecoveryMessage(2))
            database.agentPersistenceDao().rawUpdateMessage(message.copy(currentRunId = "new-run", ownershipFence = 8))
        }
        assertFalse(repository.finishProviderCheckpoint("pass", chat, run.runId, 7, WorkspaceRepository.digest(saved.toString()), "FAILED", buildJsonObject { put("state", "FAILED") }))
        assertEquals(saved, repository.protectedSynthesisCheckpoint("pass"))
        assertEquals(1, secrets.size)
    }
}
