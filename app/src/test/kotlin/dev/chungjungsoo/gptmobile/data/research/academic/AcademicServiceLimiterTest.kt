package dev.chungjungsoo.gptmobile.data.research.academic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicServiceLimiterTest {
    @Test fun ncbiSubdomainsShareOnePhysicalDispatchClock() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val limiter = AcademicServiceLimiter({ now }) { wait ->
            waits += wait
            now += wait
        }
        limiter.await("eutils.ncbi.nlm.nih.gov") { true }
        limiter.await("pubmed.ncbi.nlm.nih.gov") { true }
        limiter.await("pmc.ncbi.nlm.nih.gov") { true }
        assertEquals(listOf(350L, 350L), waits)
        assertEquals(700L, now)
    }

    @Test fun permissionRevocationDuringWaitNeverAdmitsADispatch() = runBlocking {
        var allowed = true
        val limiter = AcademicServiceLimiter({ 0 }) { allowed = false }
        limiter.await("eutils.ncbi.nlm.nih.gov") { allowed }
        assertTrue(runCatching { limiter.await("pmc.ncbi.nlm.nih.gov") { allowed } }.isFailure)
    }
}
