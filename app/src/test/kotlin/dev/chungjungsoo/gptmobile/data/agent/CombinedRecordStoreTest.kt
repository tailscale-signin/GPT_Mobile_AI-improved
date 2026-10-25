package dev.chungjungsoo.gptmobile.data.agent

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CombinedRecordStoreTest {
    @Test fun prematureEndPolicyIsPersistedOnlyForNewAcceptedAppendPlans() = runBlocking {
        for ((append, version) in listOf(true to 2, true to 1, false to 1)) {
            val request = "History of Portugal in 1000 words"
            val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = append), listOf("p"), mapOf("p" to "model"), request, singlePass = true)
            val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.SYNTHESIS, "p")
            val writer = PlatformV2(uid = "p", name = "Writer", model = "model", compatibleType = ClientType.CUSTOM, enabled = true, maxTokens = 8192, apiUrl = "https://store-continuation.example/v1")
            val system = combinedWriterSystemPrompt(null, "PASSAGES_V3", request, append && version >= 2)
            val plan = CombinedWriterRequestPlan.freeze(writer, request, snapshot.prompt, system, emptyList(), "PASSAGES_V3", 8192, appendContinuations = append).copy(version = version)
            val frozen = snapshot.copy(userMessage = snapshot.userMessage.copy(content = request), combined = context, answerContract = CombinedAnswerPolicy.from(request), writerRequestPlan = plan, inputDigest = "").seal()
            val db = database(frozen)
            try {
                val store = CombinedRecordStore.open(db.agentPersistenceDao(), runId, frozen, "revision", 100000, false)
                store.protocol.accept(first + """{"type":"end","seq":2,"id":"END","lastSeq":1}""")
                store.protocol.markAttemptFinished("COMPLETED")
                store.save(store.protocol.checkpoint())
                val expectedRecovery = append && version >= 2
                assertEquals(expectedRecovery, store.protocol.canContinueUnderlength)
                assertEquals(!expectedRecovery, store.protocol.checkpoint().ended)
                val restored = CombinedRecordStore.open(db.agentPersistenceDao(), runId, frozen, "revision", 100000, false)
                assertEquals(store.protocol.checkpoint(), restored.protocol.checkpoint())
                assertEquals(expectedRecovery, restored.protocol.canContinueUnderlength)
            } finally {
                db.close()
            }
        }
    }

    @Test fun earlierAttributionAlgorithmDoesNotInvalidateIntactCommittedRecords() = runBlocking {
        val db = database()
        try {
            val dao = db.agentPersistenceDao()
            val store = CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false)
            store.protocol.accept(first)
            store.save(store.protocol.checkpoint())
            val row = dao.combinedDeliveryCheckpoint(runId, "revision")!!
            val report = Json.decodeFromString<CombinedQualityReport>(row.attributionJson)
            val legacy = report.copy(passages = report.passages.map { it.copy(origin = "LEGACY_ATTRIBUTION", linkVersion = 0, links = emptyList()) })
            dao.upsertCombinedDeliveryCheckpoint(row.copy(attributionJson = Json.encodeToString(legacy)))
            assertEquals(store.protocol.checkpoint(), CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false).protocol.checkpoint())
            val corrupt = legacy.copy(passages = legacy.passages.map { it.copy(profileUids = setOf("unregistered-owner")) })
            dao.upsertCombinedDeliveryCheckpoint(row.copy(attributionJson = Json.encodeToString(corrupt)))
            assertTrue(runCatching { CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false) }.isFailure)
        } finally {
            db.close()
        }
    }

    private val runId = "combined-synthesis:test"
    private val first = """{"type":"passage","seq":1,"id":"P1","text":"  Exact 🦋 paragraph.  ","claims":[],"contributors":[],"evidence":[]}"""
    private val second = """{"type":"passage","seq":2,"id":"P2","text":"Later section.","claims":[],"contributors":[],"evidence":[]}"""
    private val snapshot = CombinedInputSnapshot(
        version = 6,
        userMessage = MessageV2(id = 2, chatId = 1, platformType = null, content = "Task"),
        history = emptyList(), replies = emptyList(), sources = listOf(CombinedModelResponse("p", "Profile", content = "Original source.")),
        prompt = "Frozen", profileUid = "p", model = "model", systemPrompt = "", configKey = "config",
        snapshotId = "snapshot", revisionId = "revision", deliveryProtocol = "PASSAGES_V3"
    ).seal()

    private suspend fun database(frozenSnapshot: CombinedInputSnapshot = snapshot): ChatDatabaseV2 {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Test"))
        db.messageDao().addMessages(frozenSnapshot.userMessage, MessageV2(id = 3, chatId = 1, platformType = "p", content = "", currentRunId = runId, ownershipFence = 4))
        val frozen = frozenSnapshot.encode()
        db.agentRunDao().upsert(AgentRun(runId, 1, 2, 3, "p", "CUSTOM", "model", "RUNNING", ownershipFence = 4, synthesisKey = researchHash(frozen), synthesisSnapshot = frozen))
        return db
    }

    @Test fun restartRestoresAtomicTextOffsetsRecordCursorAndPartialFragment() = runBlocking {
        val db = database()
        try {
            val dao = db.agentPersistenceDao()
            val store = CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false)
            store.protocol.accept(first + second.take(50))
            store.save(store.protocol.checkpoint())
            val row = dao.combinedDeliveryCheckpoint(runId, "revision")!!
            val checkpoint = Json.decodeFromString<CombinedRecordCheckpoint>(row.stateJson)
            val quality = Json.decodeFromString<CombinedQualityReport>(row.attributionJson)
            assertEquals(1, checkpoint.lastSequence)
            assertEquals(second.take(50), checkpoint.pending)
            assertEquals("  Exact 🦋 paragraph.  ", row.renderedAnswer)
            assertEquals("P1", quality.passages.single().passageId)
            assertTrue(quality.passages.single().matches(row.renderedAnswer))
            val restarted = CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false)
            assertTrue(restarted.restored)
            assertEquals(checkpoint, restarted.protocol.checkpoint())
        } finally {
            db.close()
        }
    }

    @Test fun racingContinuationAndCancelledOwnerCannotAdvanceTheSavedCursor() = runBlocking {
        val db = database()
        try {
            val dao = db.agentPersistenceDao()
            val firstOwner = CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false)
            val staleOwner = CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false)
            firstOwner.protocol.accept(first)
            firstOwner.save(firstOwner.protocol.checkpoint())
            staleOwner.protocol.accept(first.replace("Exact", "Changed"))
            assertTrue(runCatching { staleOwner.save(staleOwner.protocol.checkpoint()) }.isFailure)
            assertEquals("  Exact 🦋 paragraph.  ", dao.combinedDeliveryCheckpoint(runId, "revision")!!.renderedAnswer)
            db.agentRunDao().finishRunning(runId, "CANCELED", 3, null)
            firstOwner.protocol.accept(second)
            assertTrue(runCatching { firstOwner.save(firstOwner.protocol.checkpoint()) }.isFailure)
            assertEquals(1, Json.decodeFromString<CombinedRecordCheckpoint>(dao.combinedDeliveryCheckpoint(runId, "revision")!!.stateJson).lastSequence)
        } finally {
            db.close()
        }
    }

    @Test fun changedSealedInputAndCorruptedRenderedTextAreRejectedOnRecovery() = runBlocking {
        val db = database()
        try {
            val dao = db.agentPersistenceDao()
            assertTrue(runCatching { CombinedRecordStore.open(dao, runId, snapshot.copy(prompt = "Changed"), "revision", 10_000, false) }.isFailure)
            val store = CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false)
            store.protocol.accept(first)
            store.save(store.protocol.checkpoint())
            val row = dao.combinedDeliveryCheckpoint(runId, "revision")!!
            dao.upsertCombinedDeliveryCheckpoint(row.copy(renderedAnswer = "Corrupt"))
            assertTrue(runCatching { CombinedRecordStore.open(dao, runId, snapshot, "revision", 10_000, false) }.isFailure)
        } finally {
            db.close()
        }
    }
}
