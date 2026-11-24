package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceSpeechBenchmarkBatterySourceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val battery: BatteryManager get() = context.getSystemService(BatteryManager::class.java)

    @Suppress("DEPRECATION")
    private fun unplugged() {
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun energy(value: Long) = shadowOf(battery).setLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, value)

    private fun finishWithReceiverQueue(window: VoiceSpeechBenchmarkEnergyWindow): VoiceSpeechBenchmarkEnergyObservation {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<VoiceSpeechBenchmarkEnergyObservation> { window.finish() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!result.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            return result.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun unsupportedAndroidSentinelRemainsUnknownAndObserverRetires() {
        unplugged()
        energy(Long.MIN_VALUE)
        val before = shadowOf(context).registeredReceivers.size
        val window = VoiceSpeechBenchmarkBatterySource(context).begin(VoiceSpeechBackend.CPU)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val result = window.finish()
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE, result.status)
        assertNull(result.start.remainingNanowattHours)
        assertNull(result.end!!.remainingNanowattHours)
        assertNull(result.observedCounterDecreaseNanowattHours)
        assertEquals(before, shadowOf(context).registeredReceivers.size)
        assertEquals(result, window.finish())
    }

    @Test fun optionalBatteryServiceFailureDoesNotPreventBenchmarkObservationOrCleanup() {
        unplugged()
        val unavailable = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = if (name == Context.BATTERY_SERVICE) error("Unavailable") else super.getSystemService(name)
        }
        val window = VoiceSpeechBenchmarkBatterySource(unavailable).begin(VoiceSpeechBackend.CPU)
        val result = window.finish()
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE, result.status)
        assertNull(result.observedCounterDecreaseNanowattHours)
    }

    @Test fun supportedCounterReportsWholeDeviceDifferenceOnlyDuringObservedUnpluggedDischarge() {
        unplugged()
        energy(1_000_000_000)
        val window = VoiceSpeechBenchmarkBatterySource(context).begin(VoiceSpeechBackend.CPU)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        energy(998_000_000)
        val result = finishWithReceiverQueue(window)
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_DECREASE_OBSERVED, result.status)
        assertEquals(2_000_000L, result.observedCounterDecreaseNanowattHours)
        assertEquals(2000L, result.elapsedMs)
        assertTrue(result.monitoringConfirmed)
    }

    @Test fun mainThreadFinalizationCannotDiscardQueuedPowerChangesAndClaimConfirmedMonitoring() {
        unplugged()
        energy(1_000_000_000)
        val window = VoiceSpeechBenchmarkBatterySource(context).begin(VoiceSpeechBackend.CPU)
        context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        context.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        energy(998_000_000)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val result = window.finish()
        assertFalse(result.monitoringConfirmed)
        assertNull(result.observedCounterDecreaseNanowattHours)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(result, window.finish())
    }

    @Test fun interveningConnectionIsRetainedEvenWhenBothCounterEndpointsAreUnplugged() {
        unplugged()
        energy(1_000_000_000)
        val window = VoiceSpeechBenchmarkBatterySource(context).begin(VoiceSpeechBackend.CPU)
        context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        unplugged()
        energy(998_000_000)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val result = window.finish()
        assertFalse(result.start.plugged!!)
        assertFalse(result.end!!.plugged!!)
        assertTrue(result.externalPowerObserved)
        assertEquals(VoiceSpeechEnergyStatus.EXTERNAL_POWER_OBSERVED, result.status)
        assertNull(result.observedCounterDecreaseNanowattHours)
    }

    @Test fun delayedReceiverQueueCannotLaterUpgradeAnUnconfirmedCounterWindow() {
        unplugged()
        energy(1_000_000_000)
        val window = VoiceSpeechBenchmarkBatterySource(context).begin(VoiceSpeechBackend.CPU)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        energy(998_000_000)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<VoiceSpeechBenchmarkEnergyObservation> { window.finish() }.get(5, TimeUnit.SECONDS)
            assertEquals(VoiceSpeechEnergyStatus.MONITOR_UNCONFIRMED, result.status)
            assertNull(result.observedCounterDecreaseNanowattHours)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(result, window.finish())
        } finally {
            executor.shutdownNow()
        }
    }
}
