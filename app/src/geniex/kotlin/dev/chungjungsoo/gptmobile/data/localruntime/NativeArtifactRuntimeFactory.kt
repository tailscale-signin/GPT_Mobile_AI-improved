package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context

internal object NativeArtifactRuntimeFactory {
    fun create(context: Context): LocalRuntime = GenieXRuntimeAdapter(context)
}
