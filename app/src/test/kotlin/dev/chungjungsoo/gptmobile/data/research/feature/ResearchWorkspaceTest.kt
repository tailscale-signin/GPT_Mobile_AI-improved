package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.memory.ResearchRecallProvider
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchWorkspaceTest {
    @Test fun migrationPreservesVersionOneRowsAndRoomValidatesTheNewSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "workspace-migration-${System.nanoTime()}.db"
        val schemaPath = "schemas/dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase/1.json"
        val schema = Json.parseToJsonElement(listOf(File(schemaPath), File("app/$schemaPath")).first { it.isFile }.readText()).jsonObject.getValue("database").jsonObject
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO research_tombstones (`key`,epoch) VALUES ('archive:removed',42)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, ResearchDatabase::class.java, name).addMigrations(ResearchWorkspaceMigration, ResearchStudyCardsMigration, ResearchRetentionMigration).allowMainThreadQueries().build()
        try {
            assertEquals(4, db.openHelper.writableDatabase.version)
            runBlocking {
                assertEquals(42L, db.research().deleted("archive:removed"))
                db.research().handoff(ResearchHandoff("handoff", "trial", "AWAITING_IMPORT", 1))
                assertEquals("trial", db.research().handoff("handoff")?.query)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun reactiveStandaloneImportDeduplicatesProvenanceAndCannotResurrectAfterClear() = withArchive { archive ->
        val db = archive.database
        archive.initialize()
        val waiting = async { withTimeout(5000) { archive.observePage().first { it.isNotEmpty() } } }
        val paper = AcademicPaper("doi:10.1234/trial", "Clinical trial", "https://pubmed.ncbi.nlm.nih.gov/42/", doi = "10.1234/trial", identifiers = mapOf("pmid" to "42"), sourceProviders = listOf("pubmed"), providerRecordId = "42", abstract = "Participants improved.")
        val first = archive.ingestPaper(paper, "discovery:one", 0, true) { true }
        assertEquals(first.workId, waiting.await().single().workId)
        val sequence = db.research().membership(first.workId)!!.archiveSequence
        archive.move(first.workId, ResearchArchiveIngestor.DEFAULT_TOPIC)
        val second = archive.ingestPaper(paper.copy(sourceProviders = listOf("europepmc"), providerRecordId = "MED:42", abstract = ""), "discovery:two", 0, true) { true }
        assertEquals(first.workId, second.workId)
        assertEquals(1, db.research().count())
        assertEquals(sequence, db.research().membership(first.workId)!!.archiveSequence)
        assertEquals(ResearchArchiveIngestor.DEFAULT_TOPIC, db.research().membership(first.workId)!!.primaryTopicId)
        assertEquals(first.versionId, db.research().membership(first.workId)!!.latestVersionId)
        assertEquals(setOf("pubmed", "europepmc"), db.research().discoveries(first.workId).map { it.engineId }.toSet())
        assertTrue(db.research().allRoots().all { it.rootKind in setOf("archive", "import") })
        db.research().clearArchive(100)
        assertTrue(runCatching { archive.ingestPaper(paper, "late", 0, true) { true } }.isFailure)
        assertTrue(db.research().allRoots().isEmpty())
        assertEquals(0, db.research().count())
    }

    @Test fun groundedRecallRechecksConsentExclusionWithdrawalAndBodyIntegrity() = withArchive { archive ->
        val text = "The clinical trial reported improved mobility by 10 points."
        val version = archive.capture(ResearchSource("S1", "https://example.org/trial", "Clinical trial", "Read", text), text, "public", true, 7, "turn", true, 0)
        val dao = archive.dao
        dao.passage(EvidencePassage("p", version.versionId, "Results", 0, text.length, researchHash(text), text))
        val outcome = ResearchOutcome("o", version.workId, version.versionId, "mobility", "10 points", "Single trial", "[\"p\"]", "GROUNDED", 1)
        dao.outcome(outcome)
        val provider = ResearchRecallProvider(archive)
        assertTrue(provider.recall("clinical trial") { false }.outcomes.isEmpty())
        assertTrue(provider.recall("latest clinical trial") { true }.outcomes.isEmpty())
        val packet = provider.recall("clinical trial") { true }
        assertEquals(listOf(outcome), packet.outcomes)
        assertFalse(provider.validate(packet) { false })
        assertTrue(provider.validate(packet) { true })
        dao.excludeRecall(ResearchRecallExclusion(version.workId, 2))
        assertFalse(provider.validate(packet) { true })
        dao.forgetOutcome(outcome.outcomeId)
        assertTrue(provider.recall("clinical trial") { true }.outcomes.isEmpty())
    }

    @Test fun exactRecallCitationRemainsBoundAfterANewerCaptureOfTheSameSource() = withArchive { archive ->
        val body = "The clinical trial reported improved mobility by 10 points."
        val first = archive.capture(ResearchSource("S1", "https://example.org/trial", "Clinical trial", "Read", body), body, "public", true, 7, "first", true, 0)
        archive.dao.passage(EvidencePassage("p", first.versionId, "Results", 0, body.length, researchHash(body), body))
        val outcome = ResearchOutcome(researchHash("selected-outcome"), first.workId, first.versionId, "mobility", "10 points", "Single trial", "[\"p\"]", "GROUNDED", 1)
        archive.dao.outcome(outcome)
        val packet = ResearchRecallProvider(archive).recall("clinical trial") { true }
        assertTrue(packet.text.contains("[study:${outcome.outcomeId}]"))
        // A refresh after dispatch cannot rewrite the version actually supplied to the writer.
        val revised = body + " Correction: the result needs further study."
        val second = archive.capture(ResearchSource("S1", "https://example.org/trial", "Clinical trial", "Read", revised), revised, "public", true, 7, "second", true, 0)
        assertFalse(first.versionId == second.versionId)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val chats = Room.inMemoryDatabaseBuilder(app, dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        val root = kotlin.io.path.createTempDirectory("citation-recall").toFile()
        try {
            chats.chatRoomDao().addChatRoom(dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2(id = 7, title = "Second conversation"))
            val context = object : ContextWrapper(app) {
                override fun getFilesDir() = root
            }
            val resolver = CitationBindingResolver(archive, ResearchSessionStore(context, archive.database), chats)
            val message = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 9, chatId = 7, platformType = "writer", currentRunId = "recall-run", content = "The historical trial reported 10 points [study:${outcome.outcomeId}], https://example.org/trial.")
            val bindings = resolver.bind(message)
            assertEquals(2, bindings.size)
            assertEquals(setOf(first.versionId), bindings.map { it.versionId }.toSet())
            assertTrue(archive.dao.allRoots().any { it.rootKind == "citation" && it.versionId == first.versionId })
        } finally {
            chats.close()
            root.deleteRecursively()
        }
    }

    private fun withArchive(block: suspend kotlinx.coroutines.CoroutineScope.(ResearchArchiveRepository) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = kotlin.io.path.createTempDirectory("research-workspace").toFile()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        try {
            block(ResearchArchiveRepository(db, ResearchSessionStore(context, db)))
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
