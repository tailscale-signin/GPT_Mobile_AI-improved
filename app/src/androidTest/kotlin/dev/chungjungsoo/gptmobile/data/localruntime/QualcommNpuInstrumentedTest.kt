package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device test; uses the NPU backend directly, with no router fallback. */
@RunWith(AndroidJUnit4::class)
class QualcommNpuInstrumentedTest {
    @Test(timeout = 300_000)
    fun matchingSm8750ModelGeneratesOnNpu() {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("qnnModelPath").orEmpty()
        assumeTrue("Supply qnnModelPath to run real Qualcomm inference", modelPath.isNotBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Qualification requires SM8750, found ${Build.SOC_MODEL}", QualcommSocSupport.canonicalSoc(Build.SOC_MODEL) == "SM8750")
        assertTrue("Model is not readable by the app: $modelPath", File(modelPath).let { it.isFile && it.canRead() })
        val probe = QnnEnvironment.prepareForExecution(context)
        assertTrue("QNN prerequisites failed: ${probe.errorMessage}", probe.isReady)
        for (library in listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")) {
            assertTrue("Missing packaged $library", File(probe.dispatchDir, library).isFile)
        }
        val start = SystemClock.elapsedRealtime()
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.NPU(nativeLibraryDir = probe.dispatchDir),
                maxNumTokens = 1024,
                cacheDir = context.cacheDir.resolve("qnn-qualification").apply { mkdirs() }.absolutePath
            )
        )
        try {
            engine.initialize()
            engine.createConversation(ConversationConfig(maxOutputToken = 32)).use { conversation ->
                val response = conversation.sendMessage("Reply with a short greeting.").toString()
                assertTrue("NPU generated an empty response", response.isNotBlank())
                Log.i("QnnQualification", "backend=NPU soc=${Build.SOC_MODEL} firmware=${Build.FINGERPRINT} elapsedMs=${SystemClock.elapsedRealtime() - start} outputChars=${response.length}")
            }
        } finally {
            if (engine.isInitialized()) engine.close()
        }
    }
}
