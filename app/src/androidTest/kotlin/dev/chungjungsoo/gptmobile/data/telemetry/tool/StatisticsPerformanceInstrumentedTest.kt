package dev.chungjungsoo.gptmobile.data.telemetry.tool

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Raw emulator measurements are evidence of execution, not representative device performance. */
@RunWith(AndroidJUnit4::class)
class StatisticsPerformanceInstrumentedTest {
    @Test fun catalogParseAndStatisticsOverheadProduceMeasuredEvidence(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = ToolStatisticsRepository(context)
        repository.dao.subjects()
        val identity = ToolIdentity.of("acceptance_statistics_fixture")
        val root = java.util.UUID.randomUUID().toString()
        val writes = mutableListOf<Long>()
        val queries = mutableListOf<Long>()
        val accepted = mutableListOf<Boolean>()
        withContext(Dispatchers.IO) {
            repeat(100) { index ->
                val started = System.nanoTime()
                val id = repository.begin(identity, "fixture", root, "CLIENT", index.toString())
                accepted += id != null
                repository.complete(id, ToolOutcome.SUCCESS, ToolDelivery.DELIVERED, null, 1, false)
                writes += System.nanoTime() - started
                val query = System.nanoTime()
                repository.summaries(identity.key, ToolStatisticsScope("fixture")).first()
                queries += System.nanoTime() - query
            }
            val recorded = repository.dao.rollup(identity.key, -1, "fixture")
            val raw = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
            val parses = List(20) {
                val started = System.nanoTime()
                val catalog = ModelCatalogParser.parse(raw)
                assertEquals(241, catalog.source?.familyCount)
                System.nanoTime() - started
            }
            fun p95(values: List<Long>) = values.sorted()[(values.size * 0.95).toInt().coerceAtMost(values.lastIndex)] / 1_000_000.0
            val report = org.json.JSONObject().apply {
                put("schema", 1)
                put("appCommit", dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT)
                put("deviceFingerprint", android.os.Build.FINGERPRINT)
                put("hardware", android.os.Build.HARDWARE)
                put("screenWidthDp", context.resources.configuration.screenWidthDp)
                put("densityDpi", context.resources.displayMetrics.densityDpi)
                put("fontScale", context.resources.configuration.fontScale.toDouble())
                put("representativeDevice", false)
                put("samples", writes.size)
                put("qualification", "measurement_only")
                put("acknowledgedStarts", accepted.count { it })
                put("unacknowledgedStarts", accepted.count { !it })
                put("recordedTotal", recorded?.total ?: 0)
                put("recordedSuccess", recorded?.success ?: 0)
                put("recordedInProgress", recorded?.inProgress ?: 0)
                put("coverageGap", repository.coverageGap.value)
                put("acceptedStarts", org.json.JSONArray(accepted))
                put("writeAttemptP95Ms", p95(writes))
                put("summaryP95Ms", p95(queries))
                put("catalogParseP95Ms", p95(parses))
                put("writeAttemptNanos", org.json.JSONArray(writes))
                put("queryNanos", org.json.JSONArray(queries))
                put("catalogParseNanos", org.json.JSONArray(parses))
            }
            java.io.File(context.filesDir, "acceptance-0950-performance.json").writeText(report.toString(2))
            Log.i("Release0950Performance", "environment=android-emulator samples=100 write_attempt_p95_ms=${p95(writes)} acknowledged=${accepted.count { it }} unacknowledged=${accepted.count { !it }} summary_p95_ms=${p95(queries)} catalog_parse_p95_ms=${p95(parses)} representative_device=false")
        }
        // Validate accounting independently of machine-speed qualification. A deadline
        // miss is recorded in the artifact, not silently retried or hidden by warm-up.
        val totals = repository.dao.rollup(identity.key, -1, "fixture")
        assertTrue("At least one real Room observation must complete", (totals?.success ?: 0) > 0)
        assertTrue("Observation must not double count", (totals?.total ?: 0) <= 100)
        assertTrue("Successes cannot exceed total uses", (totals?.success ?: 0) <= (totals?.total ?: 0))
        repository.clear(identity.key)
    }
}
