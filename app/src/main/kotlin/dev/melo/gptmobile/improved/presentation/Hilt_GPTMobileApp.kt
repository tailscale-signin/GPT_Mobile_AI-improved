package dev.melo.gptmobile.improved.presentation

import android.app.Application
import androidx.annotation.CallSuper
import dagger.hilt.internal.GeneratedComponentManager
import dagger.hilt.internal.GeneratedComponentManagerHolder
import dagger.hilt.internal.UnsafeCasts

/**
 * Base application class providing standard Hilt entry point setup without relying
 * on bytecode manipulation during build packaging.
 */
abstract class Hilt_GPTMobileApp : Application(), GeneratedComponentManagerHolder {

    private var injected = false

    @Suppress("DEPRECATION")
    private val componentManager: dagger.hilt.android.internal.managers.ApplicationComponentManager by lazy {
        dagger.hilt.android.internal.managers.ApplicationComponentManager(
            dagger.hilt.android.internal.managers.ComponentSupplier {
                dagger.hilt.android.internal.modules.ApplicationContextModule(this)
            }
        )
    }

    override fun componentManager(): GeneratedComponentManager<*> = componentManager

    override fun generatedComponent(): Any = componentManager.generatedComponent()

    @CallSuper
    override fun onCreate() {
        hiltInternalInject()
        super.onCreate()
    }

    protected open fun hiltInternalInject() {
        if (!injected) {
            injected = true
            UnsafeCasts.<GPTMobileApp_GeneratedInjector>unsafeCast(generatedComponent())
                .injectGPTMobileApp(UnsafeCasts.unsafeCast(this))
        }
    }
}
