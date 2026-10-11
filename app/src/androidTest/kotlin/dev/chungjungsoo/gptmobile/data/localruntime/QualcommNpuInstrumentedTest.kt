package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical NPU qualification uses production guards and two cold starts, without router fallback. */
@RunWith(AndroidJUnit4::class)
class QualcommNpuInstrumentedTest {
    @Test(timeout = 600_000)
    fun matchingSm8750ModelGeneratesOnNpu() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("qnnModelPath").orEmpty()
        assumeTrue("NOT_QUALIFIED: supply qnnModelPath and qnnModelSha256 for physical qualification", modelPath.isNotBlank())
        val expected = args.getString("qnnModelSha256").orEmpty()
        assertTrue("Qualification requires a trusted model SHA-256", expected.matches(Regex("[a-fA-F0-9]{64}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Qualification requires SM8750", QualcommSocSupport.canonicalSoc(Build.SOC_MODEL) == "SM8750")
        assertTrue("Model is not readable by the app", File(modelPath).let { it.isFile && it.canRead() })
        PackageDigest.verify(File(modelPath), expected)
        val start = SystemClock.elapsedRealtime()
        repeat(2) { coldStart ->
            val runtime = LocalRuntimeQnnImpl(context)
            try {
                runtime.loadEngine(LocalEngineSpec(modelPath, LocalAccelerators.NPU, 1024))
                assertTrue(runtime.loadedEngineSpec()?.accelerator == LocalAccelerators.NPU)
                repeat(2) {
                    runtime.createConversation(LocalConversationConfig(LocalSamplerConfig(1, 1f, 0f), null, emptyList(), maxOutputTokens = 32))
                    val events = runtime.sendMessage("Reply with a short greeting.").toList()
                    assertTrue("NPU output missing", events.filterIsInstance<LocalRuntimeEvent.TextDelta>().joinToString("") { event -> event.text }.isNotBlank())
                    assertTrue("NPU generation failed", events.none { event -> event is LocalRuntimeEvent.Error })
                    assertTrue("NPU did not complete", events.any { event -> event == LocalRuntimeEvent.Done })
                    assertTrue("Production runtime did not confirm native output", QnnExecutionEvidence.executionQualified)
                    runtime.closeConversation()
                }
                Log.i("QnnQualification", "status=QUALIFIED nonempty=true backend=NPU coldStart=$coldStart modelSha256=$expected elapsedMs=${SystemClock.elapsedRealtime() - start}")
            } finally {
                runtime.unloadEngine()
            }
        }
    }
}
