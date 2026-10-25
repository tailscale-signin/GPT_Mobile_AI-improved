package dev.chungjungsoo.gptmobile.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "dev.melo.gptmobile.improved"
private fun fixture(stream: Boolean = false) = Intent().setComponent(ComponentName(PACKAGE, "dev.chungjungsoo.gptmobile.presentation.ui.benchmark.ChatBenchmarkActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("stream", stream)

@RunWith(AndroidJUnit4::class)
class AppPerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStart() = benchmark.measureRepeated(PACKAGE, listOf(StartupTimingMetric()), CompilationMode.None(), startupMode = StartupMode.COLD, iterations = 10, setupBlock = { pressHome() }) { startActivityAndWait() }

    @Test fun longChatScroll() = benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3), iterations = 8, setupBlock = { startActivityAndWait(fixture()) }) {
        check(device.wait(Until.hasObject(By.res("benchmark_chat")), 10000))
        val list = device.findObject(By.res("benchmark_chat"))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(4) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    @Test fun keyboardDuringStreaming() = benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3), iterations = 8, setupBlock = { startActivityAndWait(fixture(true)) }) {
        check(device.wait(Until.hasObject(By.res("benchmark_input")), 10000))
        repeat(4) {
            device.findObject(By.res("benchmark_input")).click()
            device.findObject(By.res("benchmark_input")).text = "Continue the saved task"
            device.pressBack()
            device.waitForIdle()
        }
    }

    @Test fun settingsDiscovery() = benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3), iterations = 8, setupBlock = { startActivityAndWait(fixture().putExtra("screen", "settings")) }) {
        check(device.wait(Until.hasObject(By.res("settings_search")), 10000))
        val field = device.findObject(By.res("settings_search"))
        field.click()
        field.text = "local"
        device.pressBack()
        val list = device.findObject(By.res("benchmark_settings"))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(2) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    @Test fun foregroundServiceStartupRace() = benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.None(), iterations = 5, setupBlock = { pressHome() }) {
        startActivityAndWait(fixture().putExtra("serviceRace", true))
        check(device.wait(Until.hasObject(By.res("benchmark_service_done")), 15000)) { "Service start/cancel/restart fixture did not finish; inspect process crash logs." }
        pressHome()
        startActivityAndWait(fixture())
        check(device.wait(Until.hasObject(By.res("benchmark_chat")), 10000))
    }

    @Test fun fiveProfileCombinedStreaming() = benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3), iterations = 8, setupBlock = { pressHome() }) {
        startActivityAndWait(fixture(true).putExtra("screen", "combined"))
        check(device.wait(Until.hasObject(By.res("benchmark_combined_streams")), 10000))
        device.findObject(By.res("benchmark_input")).click()
        device.findObject(By.res("benchmark_input")).text = "Next shared question"
        device.pressBack()
        val list = device.findObject(By.res("benchmark_combined_streams"))
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        Thread.sleep(4500)
    }

    @Test fun streamedResponse() = benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3), iterations = 8, setupBlock = { pressHome() }) {
        startActivityAndWait(fixture(true))
        // Deliberate device benchmark sampling interval, never an application delay.
        Thread.sleep(4500)
    }

    @Test fun savedCombinedTenProfiles100k() = savedCombined(10, 100_000)

    @Test fun savedCombinedTenProfiles1m() = savedCombined(10, 1_000_000)

    @Test fun savedCombinedFiftyProfiles100k() = savedCombined(50, 100_000)

    @Test fun savedCombinedFiftyProfiles1m() = savedCombined(50, 1_000_000)

    @Test fun savedCombinedHundredProfiles100k() = savedCombined(100, 100_000)

    @Test fun savedCombinedHundredProfiles1m() = savedCombined(100, 1_000_000)

    private fun savedCombined(profiles: Int, characters: Int) = benchmark.measureRepeated(
        PACKAGE,
        listOf(FrameTimingMetric()),
        CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
        iterations = 10,
        setupBlock = {
            startActivityAndWait(fixture().putExtra("screen", "saved-combined").putExtra("profiles", profiles).putExtra("characters", characters))
            check(device.wait(Until.hasObject(By.res("benchmark_saved_ready")), 30_000))
        }
    ) {
        repeat(3) {
            device.findObject(By.res("benchmark_open_final")).click()
            check(device.wait(Until.hasObject(By.res("combined_document")), 10_000))
            device.waitForIdle()
            val document = device.findObject(By.res("combined_document"))
            document.setGestureMargin(device.displayWidth / 5)
            repeat(4) {
                document.fling(Direction.DOWN)
                device.waitForIdle()
            }
            device.findObject(By.res("benchmark_contributor_tab")).click()
            check(device.wait(Until.hasObject(By.text("Original reply · Profile 0")), 10_000))
            check(device.wait(Until.hasObject(By.textStartsWith("## Profile 0")), 10_000))
            device.waitForIdle()
            device.findObject(By.text("Next page"))?.takeIf { it.isEnabled }?.let {
                it.click()
                check(device.wait(Until.hasObject(By.descStartsWith("Original reply page 2 of ")), 10_000))
            }
            device.waitForIdle()
            device.findObject(By.text("Close")).click()
            check(device.wait(Until.hasObject(By.res("combined_document")), 10_000))
            device.findObject(By.res("benchmark_final_tab")).click()
            check(device.wait(Until.hasObject(By.res("combined_document")), 10_000))
            device.findObject(By.res("benchmark_close_final")).click()
            check(device.wait(Until.hasObject(By.res("benchmark_saved_ready")), 10_000))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun generate() = profile.collect(PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        startActivityAndWait(fixture())
        check(device.wait(Until.hasObject(By.res("benchmark_chat")), 10000))
        val list = device.findObject(By.res("benchmark_chat"))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }
}
