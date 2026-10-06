package dev.chungjungsoo.gptmobile.data.diagnostics

import com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateMemoryRuntimeTest {
    @Test fun `embedding SDK uses its no-op logger and cannot load an upload backend`() {
        val logger = TasksStatsLoggerFactory.create(RuntimeEnvironment.getApplication(), "text_embedder", "default")
        assertEquals("TasksStatsDummyLogger", logger.javaClass.simpleName)
        logger.logSessionStart()
        logger.recordCpuInputArrival(100)
        logger.recordInvocationEnd(101)
        logger.logSessionEnd()
        for (name in listOf("com.google.mediapipe.tasks.core.logging.RemoteLoggingClient", "com.google.mediapipe.tasks.core.logging.TasksStatsProtoLogger", "com.google.android.datatransport.runtime.TransportRuntime")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }
}
