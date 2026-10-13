package dev.chungjungsoo.gptmobile.data.research.telemetry

import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTelemetryStoreTest {
    @Test fun transportFailuresAndCachedHitsDoNotDistortUsefulOutcomeCounts() {
        val directory = kotlin.io.path.createTempDirectory("research-metrics").toFile()
        try {
            val store = ResearchTelemetryStore(directory)
            store.record(ResearchAttempt("http", "engine", "transport_lookup", 1, outcome = "TRANSPORT_FAILED"))
            store.record(ResearchAttempt("semantic", "engine", "search", 1, outcome = "FAILED"))
            store.record(ResearchAttempt("cached", "engine", "search", 1, outcome = "SUCCESS", dispatched = false, cacheHit = true))
            store.record(ResearchAttempt("skip", "engine", "search", 1, outcome = "FAILED", dispatched = false))
            val stats = store.rollup("engine")
            assertEquals(1, stats.attempts)
            assertEquals(1, stats.failures)
            assertEquals(1, stats.transportFailures)
            assertEquals(1, stats.cached)
            assertEquals(1, stats.skipped)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyHttpSuccessIsNotUpgradedToUsefulEvidence() {
        val directory = kotlin.io.path.createTempDirectory("legacy-research-metrics").toFile()
        try {
            java.io.File(directory, "legacy.json").writeText("""{"eventId":"legacy","engineId":"engine","operation":"lookup_search","recordedAt":1,"outcome":"SUCCESS"}""")
            val stats = ResearchTelemetryStore(directory).rollup("engine")
            assertEquals(0, stats.attempts)
            assertEquals(1, stats.transportSuccesses)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun slowExportDoesNotBlockAcquisitionTelemetry() {
        val directory = kotlin.io.path.createTempDirectory("research-export").toFile()
        val executor = Executors.newFixedThreadPool(2)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val store = ResearchTelemetryStore(directory)
            store.record(ResearchAttempt("first", "engine", "search", 1, outcome = "SUCCESS"))
            val export = executor.submit {
                store.writeExport(object : OutputStream() {
                    override fun write(value: Int) {
                        writing.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        write(0)
                    }
                })
            }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            executor.submit { store.record(ResearchAttempt("second", "engine", "search", 2, outcome = "SUCCESS")) }.get(2, TimeUnit.SECONDS)
            assertEquals(2, store.rollup("engine").successes)
            release.countDown()
            export.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
