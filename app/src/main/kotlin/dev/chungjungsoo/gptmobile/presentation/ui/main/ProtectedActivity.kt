package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.security.AndroidAppLock

/** UI privacy only: background generation follows the separate background-work setting. */
open class ProtectedActivity : ComponentActivity() {
    private var unlocking = false
    private var cancellation: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.back_fade_in, R.anim.back_fade_out)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.back_fade_in, R.anim.back_fade_out)
    }

    override fun onResume() {
        super.onResume()
        val preferences = getSharedPreferences("device_privacy", MODE_PRIVATE)
        if (preferences.getBoolean("secure_screen", false) || preferences.getBoolean("app_lock", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!preferences.getBoolean("app_lock", false)) {
            authenticated = false
            window.decorView.visibility = View.VISIBLE
            return
        }
        if (unlocking) return
        if (authenticated && SystemClock.elapsedRealtime() - pausedAt < 30_000) {
            window.decorView.visibility = View.VISIBLE
            return
        }
        authenticated = false
        window.decorView.visibility = View.INVISIBLE
        if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
            finish()
            return
        }
        val challenge = try {
            AndroidAppLock.createChallenge()
        } catch (_: Exception) {
            finish()
            return
        }
        unlocking = true
        val signal = CancellationSignal().also { cancellation = it }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Unlock GPT Mobile")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        try {
            prompt.authenticate(
                BiometricPrompt.CryptoObject(challenge.signature),
                signal,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cancellation !== signal || signal.isCanceled || isFinishing || isDestroyed) return
                        authenticated = challenge.complete(result.cryptoObject?.signature)
                        cancellation = null
                        unlocking = false
                        if (!authenticated) {
                            finish()
                            return
                        }
                        pausedAt = SystemClock.elapsedRealtime()
                        window.decorView.visibility = View.VISIBLE
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cancellation !== signal || isFinishing || isDestroyed) return
                        authenticated = false
                        cancellation = null
                        unlocking = false
                        finish()
                    }
                }
            )
        } catch (_: Exception) {
            authenticated = false
            cancellation = null
            signal.cancel()
            unlocking = false
            finish()
        }
    }

    override fun onPause() {
        if (!unlocking) pausedAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onDestroy() {
        val signal = cancellation
        cancellation = null
        signal?.cancel()
        super.onDestroy()
    }

    companion object {
        private var authenticated = false
        private var pausedAt = 0L
    }
}
