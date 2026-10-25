package dev.chungjungsoo.gptmobile.data.agent

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupDatabase
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSelection
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.CombinedDocumentDatabase
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
@Config(sdk = [34], application = Application::class)
class CombinedDocumentStoreTest {
    private val runId = "combined-synthesis:document-test"
    private val original = MessageV2(id = 2, chatId = 1, platformType = null, content = "Retain the complete source answer.")
    private val source = CombinedModelResponse("p", "Profile", content = "Exact 🦋 evidence.", sourceDigest = researchHash("Exact 🦋 evidence."))
    private val snapshot = CombinedInputSnapshot(
        version = 6, userMessage = original, history = emptyList(), replies = emptyList(), sources = listOf(source),
        prompt = "Frozen", profileUid = "p", model = "model", systemPrompt = "", configKey = "config",
        snapshotId = "snapshot", revisionId = "revision", deliveryProtocol = "PASSAGES_V3"
    ).seal()

    private fun database(name: String? = null): ChatDatabaseV2 = if (name == null) {
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
    } else {
        Room.databaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java, name).build()
    }

    private suspend fun seed(db: ChatDatabaseV2) {
        db.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Durable answer"))
        db.messageDao().addMessages(original, MessageV2(id = 3, chatId = 1, platformType = "p", content = "", currentRunId = runId, ownershipFence = 4))
        val frozen = snapshot.encode()
        db.agentRunDao().upsert(AgentRun(runId, 1, 2, 3, "p", "CUSTOM", "model", "RUNNING", ownershipFence = 4, synthesisKey = researchHash(frozen), synthesisSnapshot = frozen))
    }

    private fun finalMessage(body: String): MessageV2 {
        val link = CombinedPassageLink(0, body.length, researchHash(body), setOf("p"), setOf("claim"))
        val passage = CombinedPassageRecord(0, body.length, researchHash(body), setOf("p"), origin = "LOCAL_CLAIM_MAPPING_V1", linkVersion = 1, links = listOf(link))
        val quality = CombinedQualityReport(answerDigest = researchHash(body), complete = true, status = "COMPLETED", passages = listOf(passage), revisionId = "revision", deliveredScopeStatus = "COMPLETED")
        return MessageV2(
            id = 3,
            chatId = 1,
            platformType = "p",
            content = body,
            currentRunId = runId,
            ownershipFence = 4,
            combinedSources = listOf(source),
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = body), AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = quality))
        )
    }

    @Test fun growingPayloadCheckpointsReuseImmutablePagesAndKeepEveryEarlierRevision() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.agentPersistenceDao()
            val body = StringBuilder()
            val revisions = mutableListOf<Pair<CombinedPayloadReference, String>>()
            repeat(20) { ordinal ->
                body.append(('A'.code + ordinal).toChar().toString().repeat(CombinedPayload.PAGE_CHARACTERS))
                revisions += CombinedPayload.write(dao, runId, body.toString()) to body.toString()
            }
            db.openHelper.readableDatabase.query("SELECT COUNT(*), SUM(utf8Bytes) FROM combined_durable_blocks WHERE runId=?", arrayOf(runId)).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(20, rows.getInt(0))
                assertEquals(body.length, rows.getInt(1))
            }
            revisions.forEach { (reference, expected) -> assertEquals(expected, CombinedPayload.read(dao, runId, reference.encode())) }
            val range = CombinedPayload.page(dao, runId, revisions.last().first, 11, 8)
            assertEquals(8, range.size)
            assertEquals(11 * CombinedPayload.PAGE_CHARACTERS, range.first().start)
            assertEquals(revisions.last().second.substring(range.first().start, range.last().start + range.last().text.length), range.joinToString("") { it.text })
            assertTrue(runCatching { CombinedPayload.page(dao, "different-run", revisions.last().first, 0, 1) }.isFailure)
        } finally {
            db.close()
        }
    }

    @Test fun legacyPayloadRowsRemainReadableAfterContentAddressedPageWrites() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.agentPersistenceDao()
            val body = "Legacy 🦋 source.\n".repeat(1000)
            val digest = researchHash(body)
            var previous: String? = null
            val rows = CombinedPayload.pages(body).mapIndexed { sequence, (start, text) ->
                val row = dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock(runId, "payload:$digest", sequence, start, text, researchHash(text), previous, text.toByteArray().size)
                previous = row.digest
                row
            }.toList()
            dao.insertCombinedDurableBlocks(rows)
            val legacy = CombinedPayloadReference("payload:$digest", rows.size, body.length, digest)
            assertEquals(body, CombinedPayload.read(dao, runId, legacy.encode()))
            val current = CombinedPayload.write(dao, runId, body)
            assertEquals(2, current.storageVersion)
            assertEquals(body, CombinedPayload.read(dao, runId, current.encode()))
            assertEquals(body, CombinedPayload.read(dao, runId, legacy.encode()))
        } finally {
            db.close()
        }
    }

    @Test fun millionCharacterFinalReopensAsBoundedRangesAndExportsEveryCharacter() = runBlocking {
        val name = "combined-document-${UUID.randomUUID()}"
        val body = ("Exact 🦋 evidence with whitespace.\n\n".repeat(30_000)) + "\nFinal 🦋 sentence."
        var db = database(name)
        try {
            seed(db)
            assertTrue(db.agentPersistenceDao().finishRunningWithMessage(finalMessage(body), runId, "COMPLETED", 10, null))
            val observed = db.messageDao().observeWindow(1, 0).first().single { it.id == 3 }
            val reference = requireNotNull(observed.combinedManifest)
            assertTrue(observed.content.length <= 256)
            assertTrue(observed.timeline.filter { it.type == AssistantTimelineItemType.TEXT }.all { it.content.isEmpty() })
            assertTrue(observed.combinedSources.all { it.content.isEmpty() })
            assertTrue(reference.blockCount > 100)
            db.close()
            db = database(name)
            val restarted = requireNotNull(db.messageDao().rawMessage(3))
            assertEquals(reference, restarted.combinedManifest)
            val first = CombinedDocuments.blocks(db.messageDao(), reference, 0, 4)
            val last = CombinedDocuments.blocks(db.messageDao(), reference, reference.blockCount - 1, 1).single()
            assertTrue(first.sumOf { it.text.length } <= 4 * CombinedPayload.PAGE_CHARACTERS)
            assertTrue(first.all { block -> block.passages.all { it.matches(block.text) && it.links.all { link -> link.matches(block.text) } } })
            assertEquals(body.length, last.start + last.text.length)
            assertTrue(last.text.endsWith("Final 🦋 sentence."))
            assertEquals(body, db.messageDao().message(3)!!.content)
            val exported = db.messageDao().getMessageList().single { it.id == 3 }
            assertNull(exported.combinedManifest)
            assertEquals(body, exported.content)
            assertEquals(source, exported.combinedSources.single())
            assertEquals(researchHash(body), exported.timeline.last().combinedQuality!!.answerDigest)
        } finally {
            db.close()
            RuntimeEnvironment.getApplication().deleteDatabase(name)
        }
    }

    @Test fun aStagedDocumentCannotPublishAfterItsOwnershipFenceChanges() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.agentPersistenceDao()
            val message = finalMessage("Exact evidence.\n".repeat(4000))
            val run = requireNotNull(dao.recoveryRun(runId))
            val expected = requireNotNull(dao.rawRecoveryMessage(3))
            val staged = requireNotNull(CombinedDocuments.stage(dao, message, run, "COMPLETED"))
            assertEquals(1, dao.advanceCombinedFence(runId, 4, 5))
            assertFalse(dao.commitFinishedMessage(message, runId, "COMPLETED", 10, null, expected, staged))
            assertNull(dao.rawRecoveryMessage(3)!!.combinedManifest)
            assertEquals("", dao.rawRecoveryMessage(3)!!.content)
            assertEquals(0L, dao.combinedFinalManifest(staged.manifest.id)!!.committedAt)
        } finally {
            db.close()
        }
    }

    @Test fun normalizedCheckpointKeepsPendingAndAppendMetadataWithoutAWholeBodyRow() = runBlocking {
        val db = database()
        try {
            seed(db)
            val dao = db.agentPersistenceDao()
            val store = CombinedRecordStore.open(dao, runId, snapshot, "revision", 300_000, false)
            val body = "Exact 🦋 evidence. ".repeat(5000)
            val record = """{"type":"passage","seq":1,"id":"P1","text":${Json.encodeToString(body)},"claims":[],"contributors":[],"evidence":[]}"""
            store.protocol.accept(record + "\n{\"type\":")
            store.save(store.protocol.checkpoint())
            val raw = requireNotNull(dao.rawCombinedDeliveryCheckpoint(runId, "revision"))
            assertEquals(2, raw.storageVersion)
            assertEquals(1, raw.recordCount)
            assertEquals("", raw.renderedAnswer)
            assertEquals("", raw.snapshotJson)
            assertTrue(raw.stateJson.length < 2000)
            assertTrue(Json.decodeFromString<CombinedRecordCheckpoint>(raw.stateJson).records.isEmpty())
            val restored = CombinedRecordStore.open(dao, runId, snapshot, "revision", 300_000, false)
            assertEquals(store.protocol.checkpoint(), restored.protocol.checkpoint())
            assertEquals(body, dao.combinedDeliveryCheckpoint(runId, "revision")!!.renderedAnswer)
            db.openHelper.readableDatabase.query("SELECT MAX(LENGTH(text)) FROM combined_durable_blocks").use { rows ->
                assertTrue(rows.moveToFirst())
                assertTrue(rows.getInt(0) <= CombinedPayload.PAGE_CHARACTERS)
            }
        } finally {
            db.close()
        }
    }

    @Test fun conversationOnlyBackupRetainsBodiesAndRejectsCorruptionBeforeRestore() = runBlocking {
        val sourceDb = database()
        val target = database()
        try {
            seed(sourceDb)
            val body = "Exact 🦋 evidence.\n\n".repeat(6000)
            val firstText = "Earlier distinct passage 🦋.\n".repeat(2000)
            val final = finalMessage(body)
            val timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, content = firstText), AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = "Local process note")) + final.timeline
            assertTrue(sourceDb.agentPersistenceDao().finishRunningWithMessage(final.copy(timeline = timeline), runId, "COMPLETED", 10, null))
            val selection = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
            sourceDb.withTransaction { CompleteBackupDatabase.retainSections(sourceDb.openHelper.writableDatabase, selection) }
            CombinedDocumentDatabase.validateReferences(sourceDb.openHelper.readableDatabase)
            target.withTransaction { CompleteBackupDatabase.restoreSections(sourceDb.openHelper.readableDatabase, target.openHelper.writableDatabase, selection) }
            assertEquals(body, target.messageDao().message(3)!!.content)
            assertEquals(timeline, target.messageDao().message(3)!!.timeline)
            assertEquals(source, target.messageDao().message(3)!!.combinedSources.single())
            assertNotNull(target.messageDao().rawMessage(3)!!.combinedManifest)
            val reference = requireNotNull(sourceDb.messageDao().rawMessage(3)!!.combinedManifest)
            val manifest = requireNotNull(sourceDb.agentPersistenceDao().combinedFinalManifest(reference.id))
            val payload = CombinedPayload.reference(manifest.bodyPayload)
            sourceDb.openHelper.writableDatabase.execSQL("UPDATE combined_durable_blocks SET text='Corrupted' WHERE runId=? AND payloadId=? AND sequence=0", arrayOf(runId, CombinedPayload.storageIds(payload).first()))
            assertTrue(runCatching { target.withTransaction { CompleteBackupDatabase.restoreSections(sourceDb.openHelper.readableDatabase, target.openHelper.writableDatabase, selection) } }.isFailure)
            assertEquals(body, target.messageDao().message(3)!!.content)
        } finally {
            sourceDb.close()
            target.close()
        }
    }

    @Test fun blankRetryKeepsLargeArchivedAnswerOutOfTheObservedRow() = runBlocking {
        val db = database()
        try {
            seed(db)
            val body = "Preserved earlier answer 🦋.\n\n".repeat(5000)
            val dao = db.agentPersistenceDao()
            assertTrue(dao.finishRunningWithMessage(finalMessage(body), runId, "COMPLETED", 10, null))
            val complete = requireNotNull(db.messageDao().message(3))
            val retryId = "$runId:retry"
            db.agentRunDao().upsert(requireNotNull(dao.recoveryRun(runId)).copy(runId = retryId, status = "RUNNING", ownershipFence = 5))
            val revision = dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision(body, createdAt = 10, runId = runId, timeline = complete.timeline, combinedSources = complete.combinedSources, combinedManifest = complete.combinedManifest)
            dao.updateMessage(complete.copy(content = "", thoughts = "", timeline = emptyList(), currentRunId = retryId, ownershipFence = 5, revisions = listOf(revision), activeRevisionIndex = 0))
            val observed = requireNotNull(db.messageDao().rawMessage(3))
            assertEquals("", observed.content)
            assertEquals(0, observed.combinedManifest!!.blockCount)
            assertTrue(observed.revisions.single().content.length <= 256)
            assertTrue(observed.revisions.single().combinedSources.all { it.content.isEmpty() })
            val restored = requireNotNull(db.messageDao().message(3))
            assertEquals("", restored.content)
            assertEquals(body, restored.revisions.single().content)
            val exported = db.messageDao().getMessageList().single { it.id == 3 }
            assertEquals(body, exported.revisions.single().content)
            assertNull(exported.revisions.single().combinedManifest)
        } finally {
            db.close()
        }
    }

    @Test fun backupRejectsHiddenManifestOwnerConflictsBeforeReplacingDestinationRows() = runBlocking {
        val incoming = database()
        val target = database()
        try {
            seed(incoming)
            val body = "Complete frozen contribution.\n\n".repeat(1800)
            assertTrue(incoming.agentPersistenceDao().finishRunningWithMessage(finalMessage(body), runId, "COMPLETED", 10, null))
            val reference = requireNotNull(incoming.messageDao().rawMessage(3)!!.combinedManifest)
            val revision = dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision("", createdAt = 11, runId = runId, combinedManifest = reference)
            incoming.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET revisions=? WHERE message_id=3", arrayOf(Json.encodeToString(listOf(revision, revision.copy(createdAt = 12)))))
            // Reusing an identical manifest in the same message's revision list is valid.
            CombinedDocumentDatabase.validateReferences(incoming.openHelper.readableDatabase)
            target.chatRoomDao().addChatRoom(ChatRoomV2(id = 91, title = "Destination"))
            target.messageDao().addMessages(MessageV2(id = 901, chatId = 91, platformType = null, content = "Destination remains unchanged."))
            val selection = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
            // The invalid earlier row used to be overwritten by the valid later owner.
            incoming.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET combined_manifest=? WHERE message_id=2", arrayOf(Json.encodeToString(reference)))
            assertTrue(runCatching { target.withTransaction { CompleteBackupDatabase.restoreSections(incoming.openHelper.readableDatabase, target.openHelper.writableDatabase, selection) } }.isFailure)
            assertEquals("Destination remains unchanged.", target.messageDao().rawMessage(901)!!.content)
            assertNull(target.messageDao().rawMessage(3))
            incoming.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET combined_manifest=NULL WHERE message_id=2")
            // A later matching revision must not hide a conflicting reference for that ID.
            val conflicting = revision.copy(combinedManifest = reference.copy(bodyLength = reference.bodyLength + 1))
            incoming.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET revisions=? WHERE message_id=3", arrayOf(Json.encodeToString(listOf(conflicting, revision))))
            assertTrue(runCatching { target.withTransaction { CompleteBackupDatabase.restoreSections(incoming.openHelper.readableDatabase, target.openHelper.writableDatabase, selection) } }.isFailure)
            assertEquals("Destination remains unchanged.", target.messageDao().rawMessage(901)!!.content)
            assertNull(target.messageDao().rawMessage(3))
        } finally {
            incoming.close()
            target.close()
        }
    }

    @Test fun historicalSourceInspectionAcceptsExactDuplicateReferencesAndRejectsConflictingOwners() = runBlocking {
        val db = database()
        try {
            seed(db)
            assertTrue(db.agentPersistenceDao().finishRunningWithMessage(finalMessage("Complete frozen contribution.\n\n".repeat(1800)), runId, "COMPLETED", 10, null))
            val observed = requireNotNull(db.messageDao().rawMessage(3))
            val reference = requireNotNull(observed.combinedManifest)
            val header = observed.combinedSources.single()
            val revision = dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision("", createdAt = 11, runId = runId, combinedSources = listOf(header), combinedManifest = reference)
            fun history(revisions: List<dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision>) {
                db.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET combined_manifest=NULL, combined_sources='[]', revisions=? WHERE message_id=3", arrayOf(Json.encodeToString(revisions)))
            }
            history(listOf(revision, revision.copy(createdAt = 12)))
            assertEquals(source.content, CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, 0, 1).single().text)
            assertTrue(runCatching { CombinedDocuments.sourceBlocks(db.messageDao(), reference, header.copy(sourceDigest = "different"), 0, 1) }.isFailure)
            // Only the conflicting reference carries the requested source. Matching
            // its ID alone must not borrow that source for the eligible revision.
            val conflicting = revision.copy(combinedManifest = reference.copy(bodyLength = reference.bodyLength + 1))
            history(listOf(revision.copy(combinedSources = emptyList()), conflicting))
            assertTrue(runCatching { CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, 0, 1) }.isFailure)
            db.openHelper.writableDatabase.execSQL("UPDATE messages_v2 SET combined_manifest=?, combined_sources=?, revisions='[]' WHERE message_id=3", arrayOf(Json.encodeToString(conflicting.combinedManifest), Json.encodeToString(listOf(header))))
            assertTrue(runCatching { CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, 0, 1) }.isFailure)
        } finally {
            db.close()
        }
    }

    @Test fun sourceInspectorReadsOnlyRequestedOriginalPagesUnderPublishedOwnership() = runBlocking {
        val db = database()
        try {
            seed(db)
            val originalBody = "```kotlin\n" + "val original = \"Frozen source évidence 🦋\"\n".repeat(1500) + "```\n\n" +
                "| Original | Evidence |\n| --- | --- |\n" + "| Frozen | évidence 🦋 |\n".repeat(1500) + "\nExact original ending."
            val frozenSource = source.copy(content = originalBody, sourceDigest = researchHash(originalBody))
            val frozen = snapshot.copy(sources = listOf(frozenSource), inputDigest = "").seal().encode()
            val run = requireNotNull(db.agentPersistenceDao().recoveryRun(runId))
            db.agentRunDao().upsert(run.copy(synthesisKey = researchHash(frozen), synthesisSnapshot = frozen))
            val body = "Final answer évidence.\n\n".repeat(2000)
            assertTrue(db.agentPersistenceDao().finishRunningWithMessage(finalMessage(body).copy(combinedSources = listOf(frozenSource)), runId, "COMPLETED", 10, null))
            val observed = requireNotNull(db.messageDao().rawMessage(3))
            val reference = requireNotNull(observed.combinedManifest)
            val header = observed.combinedSources.single()
            assertEquals("", header.content)
            val sourceReference = requireNotNull(header.originalDocument)
            val pages = CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, 0, 2)
            assertTrue(pages.sumOf { it.text.length } <= 2 * CombinedPayload.PAGE_CHARACTERS)
            assertEquals(originalBody.take(pages.sumOf { it.text.length }), pages.joinToString("") { it.text })
            val last = CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, sourceReference.blockCount - 1, 1).single()
            assertTrue(last.text.endsWith("Exact original ending."))
            val frames = CombinedMarkdownPaging.frames(originalBody).toList()
            val codePage = frames.indexOfFirst { it.openingFence.isNotEmpty() }
            val tablePage = frames.indexOfFirst { it.tableHeader.isNotEmpty() }
            assertTrue(codePage > 0)
            assertTrue(tablePage > codePage)
            assertEquals("```kotlin\n", CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, codePage, 1).single().openingFence)
            assertEquals("| Original | Evidence |\n| --- | --- |\n", CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, tablePage, 1).single().tableHeader)
            assertTrue(runCatching { CombinedDocuments.sourceBlocks(db.messageDao(), reference, header.copy(platformUid = "unrelated"), 0, 1) }.isFailure)
            assertEquals(frozenSource, db.messageDao().message(3)!!.combinedSources.single())
            db.withTransaction { CompleteBackupDatabase.retainSections(db.openHelper.writableDatabase, CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))) }
            CombinedDocumentDatabase.validateReferences(db.openHelper.readableDatabase)
            assertEquals(frames[tablePage].tableHeader, CombinedDocuments.sourceBlocks(db.messageDao(), reference, header, tablePage, 1).single().tableHeader)
        } finally {
            db.close()
        }
    }
}
