package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.IOException

enum class QnnRuntimeStage {
    DEVICE, MODEL_IDENTITY, MODEL_INTEGRITY, LIBRARY_FILES, NATIVE_LINK, DISPATCH_API,
    QNN_SYSTEM, FASTRPC_HTP, MODEL_CONTEXT, ENGINE_INIT, CONVERSATION, PREFILL, DECODE, SHUTDOWN
}

enum class QnnFailureCode {
    UNSUPPORTED_SOC, WRONG_PROCESS_ABI, LIBRARY_MISSING, ELF_LINK_FAILURE,
    DISPATCH_VERSION_MISMATCH, QNN_SYSTEM_VERSION_MISMATCH, DSP_LIBRARY_UNAVAILABLE,
    DSP_INITIALIZATION_FAILED, MODEL_CONTEXT_INCOMPATIBLE, NATIVE_ALLOCATION_FAILED,
    UNSAFE_ARTIFACT, PREVIOUS_NATIVE_CRASH, UNKNOWN_NATIVE_ERROR
}

class QnnRuntimeFailure(
    val stage: QnnRuntimeStage,
    val code: QnnFailureCode,
    detail: String,
    cause: Throwable? = null
) : IOException("QNN ${stage.name} failed [$code]: $detail", cause)

/** Only assign a specific diagnosis when the native/runtime message provides evidence. */
fun classifyQnnFailure(stage: QnnRuntimeStage, error: Throwable): QnnRuntimeFailure {
    if (error is QnnRuntimeFailure) return error
    val detail = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
    val code = when {
        error is LinkageError || detail.contains("dlopen") || detail.contains("undefined symbol") -> QnnFailureCode.ELF_LINK_FAILURE
        detail.contains("qnn system") && detail.contains("version") -> QnnFailureCode.QNN_SYSTEM_VERSION_MISMATCH
        detail.contains("dispatch") && (detail.contains("version") || detail.contains("api")) -> QnnFailureCode.DISPATCH_VERSION_MISMATCH
        detail.contains("skeleton") || detail.contains("skel") || detail.contains("fastrpc") -> QnnFailureCode.DSP_LIBRARY_UNAVAILABLE
        detail.contains("dsp") && (detail.contains("init") || detail.contains("queue")) -> QnnFailureCode.DSP_INITIALIZATION_FAILED
        detail.contains("allocation") || detail.contains("out of memory") -> QnnFailureCode.NATIVE_ALLOCATION_FAILED
        detail.contains("context") && (detail.contains("limit") || detail.contains("exceed")) -> QnnFailureCode.MODEL_CONTEXT_INCOMPATIBLE
        else -> QnnFailureCode.UNKNOWN_NATIVE_ERROR
    }
    return QnnRuntimeFailure(stage, code, error.message ?: "QNN runtime operation failed", error)
}
