package dev.chungjungsoo.gptmobile.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

private const val VOICE_PACKAGE = "dev.melo.gptmobile.improved"
internal fun voiceFixture() = Intent().setComponent(ComponentName(VOICE_PACKAGE, "dev.chungjungsoo.gptmobile.presentation.ui.benchmark.ChatBenchmarkActivity"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("screen", "voice")

/** Native speech latency is measured in the app; this journey measures real UI rendering only. */
internal fun MacrobenchmarkScope.voiceRenderingJourney() {
    check(device.wait(Until.hasObject(By.res("voice_benchmark_listen")), 10_000))
    device.findObject(By.res("voice_benchmark_listen")).click()
    check(device.wait(Until.hasObject(By.res("voice_benchmark_listening")), 10_000))
    check(device.wait(Until.hasObject(By.res("voice_benchmark_listening_sampled")), 10_000))
    device.findObject(By.res("voice_benchmark_reply")).click()
    check(device.wait(Until.hasObject(By.res("voice_benchmark_complete")), 10_000))
    device.findObject(By.res("voice_benchmark_transcript")).click()
    check(device.wait(Until.hasObject(By.res("voice_benchmark_document")), 10_000))
    val document = device.findObject(By.res("voice_benchmark_document"))
    document.setGestureMargin(device.displayWidth / 5)
    repeat(3) { document.fling(Direction.DOWN) }
    device.findObject(By.res("voice_benchmark_listen")).click()
    check(device.wait(Until.hasObject(By.res("voice_benchmark_listening")), 10_000))
}
