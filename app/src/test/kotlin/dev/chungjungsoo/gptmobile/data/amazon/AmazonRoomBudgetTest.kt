package dev.chungjungsoo.gptmobile.data.amazon

import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AmazonRoomBudgetTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: ChatDatabaseV2
    private lateinit var file: File
    private val clock = AmazonMutableClock()

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        file = File(folder.root, "legacy.json")
    }

    @After fun close() = database.close()

    @Test fun legacyImportPreservesAllConsumedRequestsPacingAndCooldownsExactlyOnce() = runBlocking {
        val state = AmazonBudgetState("2026-10-08", 7, clock.millis() + 5_000, mapOf("amazon.ca" to clock.millis() + 86_400_000))
        file.writeText(Json.encodeToString(state))
        val store = AmazonRoomBudgetStore(database, file)
        assertEquals(state, store.read())
        file.writeText("{now corrupt but already imported}")
        assertEquals(state, AmazonRoomBudgetStore(database, file).read())
        val budget = AmazonRequestBudget(store, clock)
        assertEquals(AmazonReadError.COOLDOWN_ACTIVE, (runCatching { budget.request(AmazonFreeMarket.CANADA, 10, { true }) { error("No I/O") } }.exceptionOrNull() as AmazonReadException).code)
        clock.advance(6_000)
        assertEquals(AmazonReadError.QUOTA_EXCEEDED, (runCatching { budget.request(AmazonFreeMarket.UNITED_STATES, 7, { true }) { error("No I/O") } }.exceptionOrNull() as AmazonReadException).code)
        assertEquals(7, store.read().usageCount)
    }

    @Test fun corruptLegacyNeverMarksImportCompleteOrResetsAllowance() = runBlocking {
        file.writeText("{broken}")
        val failure = runCatching { AmazonRoomBudgetStore(database, file).read() }.exceptionOrNull()
        assertEquals(AmazonReadError.STORAGE_ERROR, (failure as AmazonReadException).code)
        assertNull(database.amazonDao().budget())
        assertEquals("{broken}", file.readText())
    }

    @Test fun independentStoreInstancesCannotLoseConcurrentReservations() = runBlocking {
        val stores = List(10) { AmazonRoomBudgetStore(database, file) }
        stores.map { store -> async { store.update { current -> current.copy(usageDay = "2026-10-08", usageCount = current.usageCount + 1) } } }.awaitAll()
        assertEquals(10, stores.first().read().usageCount)
    }
}
