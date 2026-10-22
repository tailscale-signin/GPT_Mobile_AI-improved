package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveIngestor
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveIngestionRetryTest {
    @Test fun retryBackoffSurvivesSerializationAndRetainsOriginalSource() {
        var row = ResearchOutbox("ingest:test", """{"source":{"id":"S1"}}""", 1)
        repeat(20) { attempt ->
            row = row.failedIngestion(1000, "ARCHIVE_DATABASE_ERROR")
            val restored = row.copy(operation = Json.parseToJsonElement(row.operation).toString())
            val payload = Json.parseToJsonElement(restored.operation).jsonObject
            assertEquals("S1", payload.getValue("source").jsonObject.text("id"))
            assertFalse(restored.retryDue(1001))
            assertTrue(restored.retryDue(3_601_000))
            if (attempt == 0) assertTrue(restored.retryDue(31_000))
        }
    }

    @Test fun conflictingIdentityIsRetainedOnceAcrossProgressSavesAndRestart() = runBlocking {
        withStore { store, database, settings, context ->
            val first = ResearchSource("S1", "https://example.org/a", "A", doi = "10.1234/a")
            val second = ResearchSource("S2", "https://example.org/b", "B", doi = "10.1234/b")
            for (source in listOf(first, second)) ResearchArchiveIngestor.capture(database, source, "public", true, 1, "1:1", true, 0, researchHash(""), 0)
            val conflicted = first.copy(canonicalUrl = second.canonicalUrl)
            val journal = store.journal("run", 1, true, "1:1").scoped("public", true)
            journal.save(ResearchSnapshot("Task", sources = listOf(conflicted)))
            val receipt = database.research().saveReceipts(1).single()
            assertEquals("IDENTITY_REVIEW_REQUIRED", receipt.disposition)
            assertTrue(database.research().pendingIngestions().isEmpty())
            val conflicts = database.research().identityResolutions(receipt.workId!!)
            assertEquals(2, conflicts.size)
            assertTrue(conflicts.all { it.disposition == "IDENTITY_REVIEW_REQUIRED" })
            val originalA = database.research().aliasWork("public|doi:10.1234/a")
            val originalB = database.research().aliasWork("public|url:https://example.org/b")
            assertTrue(originalA != originalB && receipt.workId !in listOf(originalA, originalB))
            repeat(4) { journal.save(ResearchSnapshot("Task", sources = listOf(conflicted), updatedAt = it.toLong())) }
            val restored = ResearchSessionStore(context, database, settings)
            assertTrue(restored.retryPendingDiscovery())
            assertTrue(database.research().pendingIngestions().isEmpty())
            assertEquals(3, database.research().count())
            assertEquals(conflicts, database.research().identityResolutions(receipt.workId!!))
            assertEquals(conflicted, journal.load()!!.sources.single())
        }
    }

    @Test fun discoveryRetryDoesNotArchiveAfterFeatureIsDisabled() = runBlocking {
        withStore { store, database, settings, _ ->
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(researchFeature = ResearchFeatureSettings(enabled = false))
            val source = ResearchSource("S1", "https://example.org/retained", "Retained study")
            val payload = buildJsonObject {
                put("runId", "run")
                put("chatId", 1)
                put("turnId", "1:1")
                put("authority", "public")
                put("publicEvidence", true)
                put("epoch", 0)
                put("archiveEligible", true)
                put("source", source.json())
            }
            database.research().outbox(ResearchOutbox("ingest:test", payload.toString(), 1))
            assertTrue(store.retryPendingDiscovery())
            assertEquals(0, database.research().count())
            assertEquals("NOT_ELIGIBLE", database.research().saveReceipts(1).single().disposition)
            assertTrue(database.research().pendingIngestions().isEmpty())
        }
    }

    @Test fun aFailedDurableRetryIncrementsBackoffAndUpdatesItsReceipt() = runBlocking {
        withStore { store, database, _, _ ->
            val source = ResearchSource("S1", "https://example.org/missing", "Retained study", bodyChunks = listOf("a".repeat(64) + ".chunk"))
            val payload = buildJsonObject {
                put("runId", "run")
                put("chatId", 1)
                put("turnId", "1:1")
                put("authority", "public")
                put("epoch", 0)
                put("source", source.json())
            }
            database.research().outbox(ResearchOutbox("ingest:test", payload.toString(), 1))
            assertFalse(store.retryPendingDiscovery())
            val first = database.research().pendingIngestions().single()
            assertTrue(database.research().saveReceipts(1).single().reason!!.startsWith("RETAINED_BODY_UNAVAILABLE"))
            assertFalse(store.retryPendingDiscovery())
            assertEquals(first, database.research().pendingIngestions().single())
            database.research().outbox(first.copy(operation = JsonObject(Json.parseToJsonElement(first.operation).jsonObject - "retryAfterMs").toString()))
            assertFalse(store.retryPendingDiscovery())
            assertEquals("2", Json.parseToJsonElement(database.research().pendingIngestions().single().operation).jsonObject.text("retryAttempt"))
        }
    }

    private suspend fun withStore(block: suspend (ResearchSessionStore, ResearchDatabase, SettingRepository, Context) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("archive-retry").toFile()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
        }
        val database = Room.inMemoryDatabaseBuilder(app, ResearchDatabase::class.java).allowMainThreadQueries().build()
        val settings = mockk<SettingRepository>()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(researchFeature = ResearchFeatureSettings(enabled = true)).withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true)
        try {
            block(ResearchSessionStore(context, database, settings), database, settings, context)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
