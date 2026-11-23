package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkSemanticContextOwnerTest {
    @Test fun sourceScopeAloneCannotBypassAnotherOwnersBenchmarkLease() = runBlocking {
        val source = "github:benchmark:fixture:" + "a".repeat(64)
        assertFalse(benchmarkSemanticContextOwner(source))
        InferenceAdmission.shared { assertFalse(benchmarkSemanticContextOwner(source)) }
        val independent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            InferenceAdmission.benchmark {
                assertTrue(InferenceAdmission.isExclusiveBusy)
                assertTrue(benchmarkSemanticContextOwner(source))
                assertFalse(benchmarkSemanticContextOwner("github:repository:ordinary"))
                assertFalse(benchmarkSemanticContextOwner(source + "a".repeat(256)))
                assertTrue(withContext(Dispatchers.Default) { benchmarkSemanticContextOwner(source) })
                assertFalse(independent.async { benchmarkSemanticContextOwner(source) }.await())
            }
        } finally {
            independent.cancel()
        }
        assertFalse(benchmarkSemanticContextOwner(source))
        assertFalse(InferenceAdmission.isExclusiveBusy)
    }
}
