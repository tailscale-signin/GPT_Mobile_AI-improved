package dev.melo.gptmobile.improved.presentation

import android.app.Application
import androidx.annotation.CallSuper
import dagger.hilt.internal.GeneratedComponentManagerHolder
import dagger.hilt.internal.UnsafeCasts

/**
 * Base Application class providing the generated component manager holder for [GPTMobileApp].
 *
 * Defining this base class explicitly in Kotlin ensures that:
 * 1. When Hilt's bytecode transformation (the AGP transform) rewrites [GPTMobileApp]'s superclass,
 *    or when running without bytecode transformation (`enableAggregatingTask = true`), the class
 *    hierarchy is fully resolvable at compile time and runtime by D8 and ART.
 * 2. It avoids the Dalvik/ART ClassLoader failure where the transformed or generated Hilt Application
 *    class is not properly emitted or resolved during Android's AppComponentFactory instantiation.
 */
abstract class Hilt_GPTMobileApp : Application(), GeneratedComponentManagerHolder {

    @CallSuper
    override fun onCreate() {
        super.onCreate()
    }
}
