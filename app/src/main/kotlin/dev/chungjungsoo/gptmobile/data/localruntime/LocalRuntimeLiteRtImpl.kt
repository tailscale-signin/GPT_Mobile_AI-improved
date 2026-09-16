package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * LiteRT-LM runtime implementation for fallback when Qualcomm QNN is unavailable.
 */
class LocalRuntimeLiteRtImpl(
    context: Context
) : LocalRuntimeImpl(context) {
    // This class inherits all functionality from LocalRuntimeImpl
    // It's created for clarity and potential future extension
}