package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayEngineeringStoreTest {
    private lateinit var db: ChatDatabaseV2
    private val settings = mockk<SettingRepository>()
    private var saved = AppFeatureSettings()
    private var failSettingsCommit = false

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        coEvery { settings.getFeatureSettings() } answers { saved }
        coEvery { settings.mutateFeatureSettings(any()) } answers {
            if (failSettingsCommit) throw IOException("process stopped before preferences commit")
            saved = firstArg<(AppFeatureSettings) -> AppFeatureSettings>()(saved)
            saved
        }
    }

    @After fun close() = db.close()

    private fun binding(number: Int) = GatewayEngineeringBinding(
        "gateway-profile",
        "http://192.168.1.9:8090",
        GatewayEngineeringRequest("owner/repo", "work", "main", "Saved task $number", "model", chatId = 7, requestId = "command-$number", profileId = "selected-profile"),
        principalId = "owner",
        updates = listOf(GatewayEngineeringUpdate(0, "PLAN", "Accepted $number", 1.0))
    )

    @Test fun interruptedDataStoreMigrationIsIdempotentAndKeepsMoreThanSixtyFourRequests() = runBlocking {
        val legacy = (1..80).map(::binding)
        saved = saved.copy(gatewayEngineeringJobs = legacy)
        failSettingsCommit = true
        val store = GatewayEngineeringStore(db, settings)
        assertTrue(runCatching { store.migrateLegacy() }.isFailure)
        assertEquals(80, db.gatewayJobDao().bindings(null).size)
        assertEquals(legacy, saved.gatewayEngineeringJobs)
        assertNotNull(db.gatewayJobDao().migration(GatewayEngineeringStore.MIGRATION_KEY))
        failSettingsCommit = false
        store.migrateLegacy()
        store.migrateLegacy()
        assertTrue(saved.gatewayEngineeringJobs.isEmpty())
        assertEquals(80, store.bindings().size)
        assertEquals(1, db.gatewayJobDao().events("command-1").size)
    }

    @Test fun restoredLegacyBlobCannotOverwriteAnAlreadyAdvancedNormalizedBinding() = runBlocking {
        val original = binding(1)
        saved = saved.copy(gatewayEngineeringJobs = listOf(original))
        val store = GatewayEngineeringStore(db, settings)
        store.migrateLegacy()
        val advanced = original.copy(syncedRevision = 2, seenRevision = 2, updates = original.updates + GatewayEngineeringUpdate(2, "READ", "Newer server checkpoint", 2.0))
        store.save(advanced)
        saved = saved.copy(gatewayEngineeringJobs = listOf(original, binding(2)))
        store.migrateLegacy()
        assertEquals(2, store.bindings().size)
        assertEquals(2, store.bindings().first { it.request.requestId == "command-1" }.syncedRevision)
        assertEquals(listOf(0, 2), db.gatewayJobDao().events("command-1").map { it.revision })
    }

    @Test fun pendingCommandFingerprintAndOriginalMessageIdentitiesAreImmutableBeforeDispatch() = runBlocking {
        val store = GatewayEngineeringStore(db, settings)
        val pending = binding(1).copy(localUserMessageId = 11, localAssistantMessageId = 12, localRunId = "run")
        store.add(pending)
        val row = requireNotNull(db.gatewayJobDao().submission("command-1"))
        assertEquals("PENDING", row.state)
        assertEquals(11, row.userMessageId)
        assertEquals(12, row.assistantMessageId)
        assertEquals("run", row.runId)
        store.add(pending)
        assertTrue(runCatching { store.add(pending.copy(request = pending.request.copy(objective = "A different task"))) }.isFailure)
        assertEquals(row, db.gatewayJobDao().submission("command-1"))
    }
}
