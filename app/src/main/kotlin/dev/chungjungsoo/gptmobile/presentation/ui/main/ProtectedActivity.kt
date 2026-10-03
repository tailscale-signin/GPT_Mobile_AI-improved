package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** UI privacy only: background generation follows the separate background-work setting. */
open class ProtectedActivity : ComponentActivity() {
    private var unlocking = false
    private var cancellation: CancellationSignal? = null

    override fun onResume() {
        super.onResume()
        val preferences = getSharedPreferences("device_privacy", MODE_PRIVATE)
        if (preferences.getBoolean("secure_screen", false) || preferences.getBoolean("app_lock", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!preferences.getBoolean("app_lock", false)) {
            window.decorView.visibility = View.VISIBLE
            return
        }
        if (unlocking) return
        if (authenticated && SystemClock.elapsedRealtime() - pausedAt < 30_000) {
            window.decorView.visibility = View.VISIBLE
            return
        }
        window.decorView.visibility = View.INVISIBLE
        if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
            finish()
            return
        }
        unlocking = true
        val signal = CancellationSignal().also { cancellation = it }
        BiometricPrompt.Builder(this)
            .setTitle("Unlock GPT Mobile")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(
                signal,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authenticated = true
                        pausedAt = SystemClock.elapsedRealtime()
                        unlocking = false
                        window.decorView.visibility = View.VISIBLE
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        unlocking = false
                        finish()
                    }
                }
            )
    }

    override fun onPause() {
        if (!unlocking) pausedAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onDestroy() {
        cancellation?.cancel()
        super.onDestroy()
    }

    companion object {
        private var authenticated = false
        private var pausedAt = 0L
    }
}
