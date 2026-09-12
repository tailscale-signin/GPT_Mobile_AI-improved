package dev.chungjungsoo.gptmobile.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Native Memory & Thermal Watchdog Governor.
 *
 * Monitors Android system thermal events (PowerManager.OnThermalStatusChangedListener on API 29+)
 * and memory pressure levels (ComponentCallbacks2.onTrimMemory) to prevent app termination
 * by Low Memory Killer (LMK) and thermal throttling during intensive on-device or streaming LLM workloads.
 */
class ThermalAndMemoryGovernor private constructor(context: Context) : ComponentCallbacks2 {

    enum class ThrottleState {
        NORMAL,
        MODERATE,
        CRITICAL
    }

    private val _throttleState = MutableStateFlow(ThrottleState.NORMAL)
    val throttleState: StateFlow<ThrottleState> = _throttleState.asStateFlow()

    private val _lastTrimLevel = MutableStateFlow<Int?>(null)
    val lastTrimLevel: StateFlow<Int?> = _lastTrimLevel.asStateFlow()

    private val contextRef = WeakReference(context.applicationContext)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    init {
        context.applicationContext.registerComponentCallbacks(this)
        registerThermalListener(context.applicationContext)
    }

    private fun registerThermalListener(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    when (status) {
                        PowerManager.THERMAL_STATUS_SEVERE,
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                            _throttleState.value = ThrottleState.CRITICAL
                        }
                        PowerManager.THERMAL_STATUS_MODERATE -> {
                            _throttleState.value = ThrottleState.MODERATE
                        }
                        else -> {
                            if (_throttleState.value != ThrottleState.CRITICAL) {
                                _throttleState.value = ThrottleState.NORMAL
                            }
                        }
                    }
                }
                powerManager.addThermalStatusListener(listener)
                thermalListener = listener
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        _lastTrimLevel.value = level
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                _throttleState.value = ThrottleState.CRITICAL
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                if (_throttleState.value != ThrottleState.CRITICAL) {
                    _throttleState.value = ThrottleState.MODERATE
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI backgrounded, can trim image/speculative caches
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        _throttleState.value = ThrottleState.CRITICAL
    }

    fun release() {
        val context = contextRef.get() ?: return
        context.unregisterComponentCallbacks(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            thermalListener?.let { powerManager?.removeThermalStatusListener(it) }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ThermalAndMemoryGovernor? = null

        fun getInstance(context: Context): ThermalAndMemoryGovernor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThermalAndMemoryGovernor(context).also { INSTANCE = it }
            }
        }
    }
}
