package dev.chungjungsoo.gptmobile.data.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional Android counter support. No current/voltage integration or per-engine power is inferred. */
internal class VoiceSpeechBenchmarkBatterySource(context: Context) {
    private val context = context.applicationContext
    private val battery = runCatching { context.getSystemService(BatteryManager::class.java) }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())

    fun begin(backend: VoiceSpeechBackend): VoiceSpeechBenchmarkEnergyWindow = Window(backend)

    private inner class Window(private val backend: VoiceSpeechBackend) : VoiceSpeechBenchmarkEnergyWindow {
        private val lock = Any()
        private var registered = false
        private var powerEvents = 0
        private var externalPowerObserved = false
        private var unknownPowerStateObserved = false
        private var finished: VoiceSpeechBenchmarkEnergyObservation? = null
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = synchronized(lock) {
                if (finished != null) return@synchronized
                powerEvents = (powerEvents + 1).coerceAtMost(VoiceSpeechBenchmarkEnergyObservation.MAX_POWER_EVENTS)
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> externalPowerObserved = true
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val state = powerState(intent)
                        if (!state.powerStateKnown) unknownPowerStateObserved = true
                        if (state.plugged == true || state.chargeState == VoiceSpeechEnergyChargeState.CHARGING || state.chargeState == VoiceSpeechEnergyChargeState.FULL) externalPowerObserved = true
                    }
                }
            }
        }
        private val initialIntent = runCatching {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val sticky = if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, null, handler)
            }
            registered = true
            sticky
        }.getOrNull()
        private val start = endpoint(initialIntent)
        private val startedAtMs = SystemClock.elapsedRealtime()

        override fun current(): VoiceSpeechBenchmarkEnergyObservation = synchronized(lock) {
            finished ?: observation(null, false)
        }

        override fun finish(): VoiceSpeechBenchmarkEnergyObservation {
            synchronized(lock) { finished?.let { return it } }
            val end = endpoint(runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull())
            val endedAtMs = SystemClock.elapsedRealtime()
            // This synchronous method cannot drain callbacks queued behind the current main
            // callback. Retain endpoints without claiming confirmed monitoring on that path.
            if (Looper.myLooper() == handler.looper) return complete(end, endedAtMs, false)
            // Receiver callbacks share this queue. A bounded barrier drains already queued
            // power changes before finalizing this observation. Failure leaves only raw endpoints.
            val done = CountDownLatch(1)
            val accepted = handler.post {
                try {
                    complete(end, endedAtMs, true)
                } finally {
                    done.countDown()
                }
            }
            var confirmed = false
            if (accepted) {
                try {
                    confirmed = done.await(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return if (confirmed) checkNotNull(synchronized(lock) { finished }) else complete(end, endedAtMs, false)
        }

        private fun complete(end: VoiceSpeechEnergyEndpoint, endedAtMs: Long, queueConfirmed: Boolean): VoiceSpeechBenchmarkEnergyObservation = synchronized(lock) {
            finished?.let { return@synchronized it }
            val result = observation(end, queueConfirmed && registered, endedAtMs)
            finished = result
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
            result
        }

        private fun observation(end: VoiceSpeechEnergyEndpoint?, queueConfirmed: Boolean, endedAtMs: Long? = null) = VoiceSpeechBenchmarkEnergyObservation(
            requestedBackend = backend,
            start = start,
            end = end,
            elapsedMs = endedAtMs?.let { (it - startedAtMs).takeIf { elapsed -> elapsed in 0..VoiceSpeechBenchmarkEnergyObservation.MAX_WINDOW_MS } },
            monitorRegistered = registered,
            monitoringConfirmed = queueConfirmed,
            observedPowerEvents = powerEvents,
            externalPowerObserved = externalPowerObserved,
            unknownPowerStateObserved = unknownPowerStateObserved
        )
    }

    private fun endpoint(intent: Intent?): VoiceSpeechEnergyEndpoint {
        // getLongProperty returns Long.MIN_VALUE when unsupported; never convert it to zero.
        val remaining = runCatching { battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }.getOrNull()?.takeIf { it >= 0 }
        return powerState(intent).copy(remainingNanowattHours = remaining)
    }

    private fun powerState(intent: Intent?): VoiceSpeechEnergyEndpoint {
        val status = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_STATUS) }?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: runCatching { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) }.getOrNull()
        val chargeState = when (status) {
            BatteryManager.BATTERY_STATUS_DISCHARGING -> VoiceSpeechEnergyChargeState.DISCHARGING
            BatteryManager.BATTERY_STATUS_CHARGING -> VoiceSpeechEnergyChargeState.CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> VoiceSpeechEnergyChargeState.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> VoiceSpeechEnergyChargeState.NOT_CHARGING
            else -> VoiceSpeechEnergyChargeState.UNKNOWN
        }
        val plugged = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 }
        return VoiceSpeechEnergyEndpoint(chargeState = chargeState, plugged = plugged)
    }
}
