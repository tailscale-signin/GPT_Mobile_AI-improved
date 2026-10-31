package dev.chungjungsoo.gptmobile.presentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlin.math.max

object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0
    private val mutableVisibility = kotlinx.coroutines.flow.MutableStateFlow(false)
    val visibility: kotlinx.coroutines.flow.StateFlow<Boolean> get() = mutableVisibility

    val isBackgrounded: Boolean
        get() = !mutableVisibility.value

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        mutableVisibility.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = max(0, startedActivities - 1)
        mutableVisibility.value = startedActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
