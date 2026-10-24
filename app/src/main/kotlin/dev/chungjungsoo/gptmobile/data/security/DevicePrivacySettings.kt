package dev.chungjungsoo.gptmobile.data.security

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DevicePrivacySnapshot(val secureScreen: Boolean, val appLock: Boolean)

/** Null means protected and not yet loaded, never an unlocked default. */
internal object DevicePrivacySettings {
    private val started = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow<DevicePrivacySnapshot?>(null)
    val state = mutable.asStateFlow()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val application = context.applicationContext
        scope.launch {
            val preferences = application.getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
            fun publish() {
                mutable.value = DevicePrivacySnapshot(preferences.getBoolean("secure_screen", false), preferences.getBoolean("app_lock", false))
            }
            val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> publish() }
            listener = observer
            preferences.registerOnSharedPreferenceChangeListener(observer)
            publish()
        }
    }
}
