package dev.chungjungsoo.gptmobile.data.backup

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.amazon.AmazonBudgetEntity
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AmazonBackupTest {
    @Test fun amazonSectionIsOptionalAndOperationalTablesAreNeverExportedOrRestored() = runBlocking {
        assertFalse(CompleteBackupSelection().includes(CompleteBackupSection.AMAZON_DATA))
        val context = RuntimeEnvironment.getApplication()
        val source = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        try {
            val sourceRepository = AmazonHistoryRepository(source, Clock.systemUTC())
            sourceRepository.saveWatch("owner", dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket.CANADA, "B000000001", "80", { true })
            source.amazonDao().addObservation(dev.chungjungsoo.gptmobile.data.amazon.AmazonObservationEntity("observation", "owner", "request", "listing-series", "amazon.ca", "B000000001", "Headphones", "90.00", "CAD", "product_page", 1))
            source.amazonDao().saveBudget(AmazonBudgetEntity(stateJson = "incoming usage must not replace this device"))
            target.amazonDao().saveBudget(AmazonBudgetEntity(stateJson = "current usage and cooldowns"))
            withContext(Dispatchers.IO) {
                val selection = CompleteBackupSelection(setOf(CompleteBackupSection.AMAZON_DATA))
                CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, selection)
                assertEquals(null, source.amazonDao().budget())
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, selection)
            }
            assertEquals("current usage and cooldowns", target.amazonDao().budget()?.stateJson)
            val restored = target.amazonDao().watches("owner").single()
            assertEquals("90.00", target.amazonDao().history("owner", "amazon.ca", "B000000001", 10).single().amount)
            assertEquals("80", restored.targetAmount)
            assertEquals("ORPHANED", restored.state)
            assertEquals("BACKUP_RESTORED", restored.lastOutcome)
            assertEquals(null, restored.lastAttemptAt)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test fun restoredMatchingOwnersArePausedWithoutOverwritingCurrentUsage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
        try {
            target.platformDao().addPlatform(dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = "owner", name = "Existing profile"))
            val sourceRepository = AmazonHistoryRepository(source, Clock.systemUTC())
            sourceRepository.saveWatch("owner", dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket.CANADA, "B000000001", "80", { true })
            target.amazonDao().saveBudget(AmazonBudgetEntity(stateJson = "this installation's allowance"))
            withContext(Dispatchers.IO) {
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, CompleteBackupSelection(setOf(CompleteBackupSection.AMAZON_DATA)))
            }
            assertEquals("PAUSED", target.amazonDao().watches("owner").single().state)
            assertEquals("this installation's allowance", target.amazonDao().budget()?.stateJson)
            assertEquals("Existing profile", target.platformDao().getPlatformByUid("owner")?.name)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test fun restoreRevokesOnlyAmazonGrantsAndPreservesUnknownSettings() {
        val enabled = AppFeatureSettings().withToolPluginEnabled(ToolPluginId.AMAZON_FREE, true).withProfileToolPluginEnabled("owner", ToolPluginId.AMAZON_FREE, true).withProfileToolPluginEnabled("owner", ToolPluginId.GITHUB, true)
        val raw = Json.encodeToString(enabled).dropLast(1) + ",\"futureSetting\":42}"
        val restored = AmazonRestorePolicy.disableGrants(mapOf("advanced_feature_settings_json" to BackupValue("string", raw), "unrelated" to BackupValue("string", "kept")))
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<AppFeatureSettings>(restored.getValue("advanced_feature_settings_json").value)
        assertFalse(decoded.isToolPluginEnabled(ToolPluginId.AMAZON_FREE))
        assertFalse(decoded.isToolPluginSelected("owner", ToolPluginId.AMAZON_FREE))
        assertTrue(decoded.isToolPluginSelected("owner", ToolPluginId.GITHUB))
        assertTrue(restored.getValue("advanced_feature_settings_json").value.contains("\"futureSetting\":42"))
        assertEquals("kept", restored.getValue("unrelated").value)
    }
}
