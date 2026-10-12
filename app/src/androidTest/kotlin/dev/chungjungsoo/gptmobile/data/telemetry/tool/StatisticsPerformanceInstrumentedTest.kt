package dev.chungjungsoo.gptmobile.data.telemetry.tool

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** Raw emulator measurements are evidence of execution, not representative device performance. */
class StatisticsPerformanceInstrumentedTest {
    @Test fun catalogParseAndStatisticsOverheadProduceMeasuredEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = ToolStatisticsRepository(context)
        repository.dao.subjects()
        val identity = ToolIdentity.of("acceptance_statistics_fixture")
        val root = java.util.UUID.randomUUID().toString()
        val writes = mutableListOf<Long>()
        val queries = mutableListOf<Long>()
        withContext(Dispatchers.IO) {
            repeat(100) { index ->
                val started = System.nanoTime()
                val id = requireNotNull(repository.begin(identity, "fixture", root, "CLIENT", index.toString()))
                repository.complete(id, ToolOutcome.SUCCESS, ToolDelivery.DELIVERED, null, 1, false)
                writes += System.nanoTime() - started
                val query = System.nanoTime()
                repository.summaries(identity.key, ToolStatisticsScope("fixture")).first()
                queries += System.nanoTime() - query
            }
            val raw = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
            val parses = List(20) {
                val started = System.nanoTime()
                val catalog = ModelCatalogParser.parse(raw)
                assertEquals(241, catalog.source?.familyCount)
                System.nanoTime() - started
            }
            fun p95(values: List<Long>) = values.sorted()[(values.size * 0.95).toInt().coerceAtMost(values.lastIndex)] / 1_000_000.0
            Log.i("Release0950Performance", "environment=android-emulator samples=100 write_p95_ms=${p95(writes)} summary_p95_ms=${p95(queries)} catalog_parse_p95_ms=${p95(parses)} representative_device=false")
        }
        repository.clear(identity.key)
    }
}
