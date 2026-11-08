package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations
import dev.chungjungsoo.gptmobile.data.database.RunPolicySnapshotDatabase
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayWorkPersistenceTest {
    private class Preferences {
        var value = AppFeatureSettings()
        var cleanupFailure = false
        val settings = mockk<SettingRepository>()
        init {
            coEvery { settings.getFeatureSettings() } answers { this@Preferences.value }
            coEvery { settings.mutateFeatureSettings(any()) } coAnswers {
                if (cleanupFailure) error("Interrupted after Room commit")
                firstArg<(AppFeatureSettings) -> AppFeatureSettings>()(this@Preferences.value).also { this@Preferences.value = it }
            }
        }
    }

    private fun memory(): ChatDatabaseV2 = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).addCallback(RunPolicySnapshotDatabase.callback).build()
    private fun request(id: String = "request") = GatewayEngineeringRequest("owner/repo", "feature", "feature", "Read the repository", "exact-model", chatId = 7, requestId = id, profileId = "profile", scope = "read", profileName = "Original profile", sourceBranch = "feature", sourceSha = "a".repeat(40))
    private fun binding(id: String = "request") = GatewayEngineeringBinding("gateway-profile", "http://127.0.0.1:8081/v1", request(id), principalId = "principal", gatewayId = "gateway", identityScope = "account", userMessageId = 11, assistantMessageId = 12, localRunId = "request")
    private fun accepted() = binding().copy(conversationId = "conversation", job = GatewayEngineeringJob("eng_" + "c".repeat(32), "owner/repo", "feature", 7, "COMPLETED", 2, 1.0, 100.0, "Done", gatewayId = "gateway", principalId = "principal", conversationId = "conversation", acceptedAtMs = 1000, serverTimeMs = 2000, schedulingState = "completed", conversationRevision = 2, sourceBranch = "feature", sourceSha = "a".repeat(40)))
    private suspend fun seed(db: ChatDatabaseV2) {
        db.chatRoomDao().addChatRoom(ChatRoomV2(id = 7, title = "Original conversation", enabledPlatform = listOf("profile")))
        db.gatewayWorkDao().insertChatMessage(MessageV2(id = 11, chatId = 7, platformType = null, content = "Read the repository"))
        db.gatewayWorkDao().insertChatMessage(MessageV2(id = 12, chatId = 7, platformType = "profile", linkedMessageId = 11, currentRunId = "request", content = ""))
        db.agentRunDao().upsert(AgentRun("request", 7, 11, 12, "profile", "LLAMA", "exact-model"))
    }
    private fun parts(content: String): List<GatewayConversationMessage> {
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        val chunks = content.chunked(128 * 1024)
        return listOf(GatewayConversationMessage("user", "conversation", requireNotNull(accepted().job).id, 1, "user", "user", "Read the repository", 1000)) + chunks.mapIndexed { index, text -> GatewayConversationMessage("part-$index", "conversation", requireNotNull(accepted().job).id, index + 2L, "assistant", "final", text, 2000, "logical-answer", index, chunks.size, hash) }
    }
    private fun page(messages: List<GatewayConversationMessage>, latest: Long, more: Boolean) = GatewayMessagePage("gateway", "principal", "conversation", 2, messages, "cursor-${messages.last().sequence}", more, 1, latest, true, serverTimeMs = 2000)

    @Test fun multipartReplaySurvivesRestartAndProjectsOriginalAssistantExactlyOnce() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "gateway-replay-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).addCallback(RunPolicySnapshotDatabase.callback).build()
        val preferences = Preferences()
        var db = open()
        val content = "Complete retained answer αβ\n".repeat(100_000)
        val all = parts(content)
        try {
            seed(db)
            var store = GatewayWorkStore(db, preferences.settings)
            store.prepare(binding())
            store.accept(accepted())
            val scope = requireNotNull(db.gatewayWorkDao().scopes().singleOrNull())
            val conversation = db.gatewayWorkDao().conversations(7).single()
            store.replay(scope, conversation.id, page(all.take(5), all.last().sequence, true))
            assertEquals("", db.gatewayWorkDao().chatMessage(12)?.content)
            assertEquals("WAITING_REMOTE_JOB", db.agentRunDao().getById("request")?.status)
            assertEquals(5L, db.gatewayWorkDao().cursor(conversation.id)?.sequence)
            db.close()
            db = open()
            store = GatewayWorkStore(db, preferences.settings)
            store.replay(scope, conversation.id, page(all.take(5), all.last().sequence, true))
            val remaining = all.drop(5).chunked(8)
            remaining.forEachIndexed { index, messages -> store.replay(scope, conversation.id, page(messages, all.last().sequence, index != remaining.lastIndex)) }
            val projected = requireNotNull(db.gatewayWorkDao().chatMessage(12))
            assertTrue(projected.content.contains("gptmobile://gateway-transcript/${conversation.id}"))
            assertTrue(projected.content.length < 1000)
            assertEquals(content, db.gatewayWorkDao().storedMessages(conversation.id, 1).joinToString("") { it.content })
            assertEquals("COMPLETED", db.agentRunDao().getById("request")?.status)
            assertEquals(2, db.messageDao().rawLoadMessages(7).size)
            store.replay(scope, conversation.id, page(remaining.last(), all.last().sequence, false))
            assertEquals(2, db.messageDao().rawLoadMessages(7).size)
            assertEquals(all.last().sequence, db.gatewayWorkDao().cursor(conversation.id)?.sequence)
            assertTrue(db.gatewayWorkDao().messageParts(conversation.id, "logical-answer", 0).all { it.contentVerified && it.projectionComplete && it.localMessageId == 12 })
            val completedConversation = db.gatewayWorkDao().conversation(conversation.id)
            val completedCursor = db.gatewayWorkDao().cursor(conversation.id)
            assertTrue(requireNotNull(completedConversation).historyComplete)
            assertNull(completedConversation.historyLimitation)
            store.replay(scope, conversation.id, page(all.take(5), all.last().sequence, true).copy(serverTimeMs = 1500, historyComplete = false, historyLimitation = "Old incomplete page"))
            assertEquals(completedConversation, db.gatewayWorkDao().conversation(conversation.id))
            assertEquals(completedCursor, db.gatewayWorkDao().cursor(conversation.id))
            assertEquals("COMPLETED", db.agentRunDao().getById("request")?.status)
            assertEquals(2, db.messageDao().rawLoadMessages(7).size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun corruptLastPartRollsBackItsMessageAndCursorAndDoesNotPublishPartialAnswer() = runBlocking {
        val db = memory()
        try {
            seed(db)
            val store = GatewayWorkStore(db, Preferences().settings)
            store.prepare(binding())
            store.accept(accepted())
            val scope = db.gatewayWorkDao().scopes().single()
            val conversation = db.gatewayWorkDao().conversations(7).single()
            val all = parts("x".repeat(300_000))
            store.replay(scope, conversation.id, page(all.dropLast(1), all.last().sequence, true))
            val before = db.gatewayWorkDao().cursor(conversation.id)
            assertTrue(runCatching { store.replay(scope, conversation.id, page(listOf(all.last().copy(content = "corrupt")), all.last().sequence, false)) }.isFailure)
            assertEquals(before, db.gatewayWorkDao().cursor(conversation.id))
            assertNull(db.gatewayWorkDao().message(conversation.id, all.last().sequence))
            assertEquals("", db.gatewayWorkDao().chatMessage(12)?.content)
            assertEquals("WAITING_REMOTE_JOB", db.agentRunDao().getById("request")?.status)
        } finally {
            db.close()
        }
    }

    @Test fun legacyImportCommitsBeforePreferenceRemovalAndRetainsRequestIdentityAcrossRetry() = runBlocking {
        val db = memory()
        try {
            seed(db)
            val preferences = Preferences()
            preferences.value = preferences.value.copy(gatewayEngineeringJobs = listOf(binding().copy(localRunId = null)))
            preferences.cleanupFailure = true
            val store = GatewayWorkStore(db, preferences.settings)
            assertTrue(runCatching { store.importLegacy() }.isFailure)
            assertEquals("request", db.gatewayWorkDao().bindings().single().requestId)
            assertEquals("UNKNOWN", db.gatewayWorkDao().command("request")?.state)
            assertEquals(1, preferences.value.gatewayEngineeringJobs.size)
            preferences.cleanupFailure = false
            store.importLegacy()
            assertTrue(preferences.value.gatewayEngineeringJobs.isEmpty())
            assertEquals(1, db.gatewayWorkDao().bindings().size)
            assertEquals("request", store.bindings().single().request.requestId)
        } finally {
            db.close()
        }
    }

    @Test fun gatewayFilterRunsBeforeLimitAndBackgroundCatalogDoesNotMarkSeen() = runBlocking {
        val db = memory()
        try {
            val store = GatewayWorkStore(db, Preferences().settings)
            val selected = store.verifiedScope("selected", "http://127.0.0.1:8081/v1", GatewayIdentity(1, "gateway", "instance", "principal", "account", 1000))
            val other = store.verifiedScope("other", "http://127.0.0.1:8082/v1", GatewayIdentity(1, "other", "instance", "principal", "account", 1000))
            fun item(id: String, time: Long) = GatewayCatalogJob(id, "chat", "conversation-$id", 1, "completed", "completed", profileId = "original", profileName = "Original", model = "model", acceptedAtMs = time, updatedAtMs = time, serverTimeMs = time, latestMessageSequence = 3, historyComplete = true, conversationRevision = 3)
            store.acceptCatalog(selected, GatewayJobCatalog("gateway", "principal", 1000, listOf(item("selected-job", 1))))
            store.acceptCatalog(other, GatewayJobCatalog("other", "principal", 1000, (1..60).map { item("other-$it", it + 10L) }))
            val saved = store.jobsForGateway("selected", 50).first().single()
            assertEquals("selected-job", saved.job.jobId)
            assertEquals(0L, saved.conversation.seenMessageSequence)
            assertEquals(0L, saved.conversation.seenRevision)
            db.gatewayWorkDao().markSeen(saved.conversation.id, 3, 3)
            store.acceptCatalog(selected, GatewayJobCatalog("gateway", "principal", 1000, listOf(item("selected-job", 1))))
            assertEquals(3L, db.gatewayWorkDao().conversation(saved.conversation.id)?.seenMessageSequence)
        } finally {
            db.close()
        }
    }

    @Test fun sameJobIdentifierInTwoEnginesRetainsBothScopedRecords() = runBlocking {
        val db = memory()
        try {
            val store = GatewayWorkStore(db, Preferences().settings)
            val scope = store.verifiedScope("gateway-profile", "http://127.0.0.1:8081/v1", GatewayIdentity(1, "gateway", "instance", "principal", "account", 1000))
            val engineering = GatewayCatalogJob("same-id", "engineering", "engineering-conversation", 1, "PLAN", "queued", profileId = "profile", model = "model", acceptedAtMs = 1000, updatedAtMs = 1000, serverTimeMs = 1000)
            val ordinary = engineering.copy(kind = "chat", conversationId = "ordinary-conversation", phase = "running", schedulingState = "running")
            store.acceptCatalog(scope, GatewayJobCatalog("gateway", "principal", 1000, listOf(engineering, ordinary)))
            val rows = store.jobsForGateway("gateway-profile", 50).first()
            assertEquals(setOf("engineering", "chat"), rows.map { it.job.kind }.toSet())
            assertEquals(2, rows.map { it.row.id }.distinct().size)
            assertEquals("engineering-conversation", db.gatewayWorkDao().job(scope.id, "engineering", "same-id")?.let { db.gatewayWorkDao().conversation(it.conversationKey)?.conversationId })
            assertEquals("ordinary-conversation", db.gatewayWorkDao().job(scope.id, "chat", "same-id")?.let { db.gatewayWorkDao().conversation(it.conversationKey)?.conversationId })
        } finally {
            db.close()
        }
    }

    @Test fun ordinaryJobOpensItsOriginalChatWithoutTakingTheCoordinatorsReplyFence() = runBlocking {
        val db = memory()
        try {
            seed(db)
            val preferences = Preferences()
            val store = GatewayWorkStore(db, preferences.settings)
            val endpoint = "http://127.0.0.1:8081/v1"
            val gateway = PlatformV2(uid = "gateway-profile", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = endpoint, model = "exact-model")
            coEvery { preferences.settings.fetchPlatformV2s() } returns listOf(gateway)
            val run = requireNotNull(db.agentRunDao().getById("request")).copy(status = "RUNNING", gatewayJobId = "ordinary-job", gatewayBaseUrl = endpoint)
            db.agentRunDao().upsert(run)
            val original = requireNotNull(db.gatewayWorkDao().chatMessage(12)).copy(content = "Original local stream")
            db.gatewayWorkDao().updateChatMessage(original)
            val scope = store.verifiedScope(gateway.uid, endpoint, GatewayIdentity(1, "gateway", "instance", "principal", "account", 1000))
            val job = GatewayCatalogJob("ordinary-job", "chat", "conversation", 5, "completed", "completed", profileId = "profile", profileName = "Original profile", model = "exact-model", acceptedAtMs = 1000, updatedAtMs = 2000, serverTimeMs = 2000, latestMessageSequence = 1)
            store.acceptCatalog(scope, GatewayJobCatalog("gateway", "principal", 2000, listOf(job)))
            val conversation = db.gatewayWorkDao().conversations(7).single()
            assertEquals("ANDROID_RUN", conversation.projectionOwner)
            val result = GatewayConversationMessage("saved-final", "conversation", "ordinary-job", 1, "assistant", "final", "Saved terminal answer", 2000)
            store.replay(scope, conversation.id, page(listOf(result), 1, false))
            assertEquals(original, db.gatewayWorkDao().chatMessage(12))
            assertEquals(run, db.agentRunDao().getById("request"))
            assertEquals(2, db.messageDao().rawLoadMessages(7).size)
            val client = mockk<GatewayConversationClient>()
            coEvery { client.identity(any()) } throws IOException("offline")
            val engineering = mockk<GatewayEngineeringClient>()
            val repository = GatewayConversationRepository(preferences.settings, store, client, engineering, db)
            assertEquals(7, repository.openConversation(conversation.id, null))
            assertEquals("exact-model", db.gatewayWorkDao().chatModel(7, "profile"))
            assertEquals(1, db.chatRoomDao().getChatRooms().size)
            assertEquals(original, db.gatewayWorkDao().chatMessage(12))
            coVerify(exactly = 0) { engineering.create(any(), any()) }
            coVerify(exactly = 0) { client.followUp(any(), any(), any(), any(), any(), any()) }
        } finally {
            db.close()
        }
    }

    @Test fun openingRemoteMirrorRestoresOriginalModelAndReasoningWithoutEditingProfile() = runBlocking {
        val db = memory()
        try {
            val preferences = Preferences()
            val store = GatewayWorkStore(db, preferences.settings)
            val gateway = PlatformV2(uid = "gateway-profile", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8081/v1", model = "gateway")
            val edited = gateway.copy(uid = "profile", name = "Edited profile", model = "new-default", reasoning = true)
            coEvery { preferences.settings.fetchPlatformV2s() } returns listOf(gateway, edited)
            val scope = store.verifiedScope(gateway.uid, gateway.apiUrl, GatewayIdentity(1, "gateway", "instance", "principal", "account", 1000))
            val frozen = buildJsonObject { put("generation_parameters", buildJsonObject { put("reasoning", false) }) }
            val job = GatewayCatalogJob("remote-job", "engineering", "remote-conversation", 1, "COMPLETED", "completed", profileId = "profile", profileName = "Original profile", model = "original-model", repository = "owner/repo", branch = "feature", updatedAtMs = 1000, serverTimeMs = 1000, executionBinding = frozen, sourceBranch = "feature", sourceSha = "a".repeat(40))
            store.acceptCatalog(scope, GatewayJobCatalog("gateway", "principal", 1000, listOf(job)))
            val saved = store.jobsForGateway(gateway.uid, 50).first().single().conversation
            val client = mockk<GatewayConversationClient>()
            coEvery { client.identity(any()) } throws IOException("offline")
            val repository = GatewayConversationRepository(preferences.settings, store, client, mockk(), db)
            val id = repository.openConversation(saved.id, "github")
            assertEquals("original-model", db.gatewayWorkDao().chatModel(id, "profile"))
            assertEquals(false, preferences.value.conversationReasoning[id])
            assertEquals("feature", preferences.value.conversationGitHub[id]?.ref)
            assertEquals("a".repeat(40), preferences.value.conversationGitHub[id]?.commitSha)
            assertEquals("new-default", preferences.settings.fetchPlatformV2s().single { it.uid == "profile" }.model)
            assertEquals(frozen.toString(), db.gatewayWorkDao().conversation(saved.id)?.executionBinding)
            db.gatewayWorkDao().putChatModel(dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2(id, "profile", "explicit-new-choice"))
            assertTrue(runCatching { repository.openConversation(saved.id, "github") }.isFailure)
            assertEquals("explicit-new-choice", db.gatewayWorkDao().chatModel(id, "profile"))
        } finally {
            db.close()
        }
    }

    @Test fun migration49To50PreservesMessagesAndCreatesNormalizedGatewayTables() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "gateway-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { requireNotNull(it.parentFile).mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = File(System.getProperty("room.schemas"), "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/49.json").readText()
            val data = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
            data.getValue("entities").jsonArray.forEach { entry ->
                val entity = entry.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index -> old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
                entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { old.execSQL(it.jsonPrimitive.content) }
            }
            old.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at) VALUES(7,'Original conversation','profile',1,1)")
            old.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at) VALUES(11,7,'','Preserved message','[]',0,1)")
            old.version = 49
        }
        val db = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).addMigrations(ChatDatabaseV2Migrations.MIGRATION_49_50).build()
        try {
            assertEquals("Preserved message", db.gatewayWorkDao().chatMessage(11)?.content)
            assertEquals(50, db.openHelper.readableDatabase.version)
            assertTrue(db.gatewayWorkDao().scopes().isEmpty())
            assertTrue(db.gatewayWorkDao().bindings().isEmpty())
            val scope = GatewayScopeRecord("scope", "profile", "http://127.0.0.1/v1", "gateway", "principal", "account", "OFFLINE", 0)
            db.gatewayWorkDao().putScope(scope)
            assertNotNull(db.gatewayWorkDao().scope("scope"))
            assertFalse(db.gatewayWorkDao().chat(7)?.isTemporary ?: true)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
