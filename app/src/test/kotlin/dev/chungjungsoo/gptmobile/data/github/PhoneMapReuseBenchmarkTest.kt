package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapReuseBenchmarkTest {
    @Test fun reportsNetworkWorkAndCacheIsolationAcrossRealMapSessions() = runBlocking {
        val results = PhoneMapReuseBenchmark.run()
        // Retained by the Gradle JUnit report for comparisons between CI runs.
        println("scenario,trials,passed,max_commit_reads,max_tree_reads,max_blob_reads,p95_ns,max_cache_charge_bytes")
        results.forEach {
            println("${it.scenario},${it.trials},${it.passed},${it.commitReads},${it.treeReads},${it.blobReads},${it.p95Nanos},${it.maxCacheChargeBytes}")
        }
        assertEquals(
            listOf("First chat", "Second chat", "Branch advanced", "Account switched", "After LRU eviction"),
            results.map { it.scenario }
        )
        assertEquals(listOf(1, 0, 1, 1, 1), results.map { it.treeReads })
        assertEquals(listOf(2, 0, 1, 2, 2), results.map { it.blobReads })
        for (row in results) {
            assertEquals(row.scenario, 1, row.commitReads)
            assertEquals(row.scenario, PhoneMapReuseBenchmark.TRIALS, row.passed)
            assertTrue(row.p95Nanos > 0 && row.maxCacheChargeBytes > 0)
        }
    }
}
