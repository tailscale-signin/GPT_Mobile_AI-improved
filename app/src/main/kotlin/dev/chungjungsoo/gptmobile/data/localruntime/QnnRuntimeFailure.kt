package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.IOException

enum class QnnRuntimeStage {
    DEVICE,
    MODEL_IDENTITY,
    MODEL_INTEGRITY,
    LIBRARY_FILES,
    NATIVE_LINK,
    DISPATCH_API,
    QNN_SYSTEM,
    FASTRPC_HTP,
    MODEL_CONTEXT,
    CRASH_JOURNAL,
    UNKNOWN_WITHIN_NATIVE_INITIALIZE,
    ENGINE_INIT,
    CONVERSATION,
    PREFILL,
    DECODE,
    SHUTDOWN
}

enum class QnnFailureCode {
    UNSUPPORTED_SOC,
    WRONG_PROCESS_ABI,
    LIBRARY_MISSING,
    ELF_LINK_FAILURE,
    DISPATCH_VERSION_MISMATCH,
    QNN_SYSTEM_VERSION_MISMATCH,
    DSP_LIBRARY_UNAVAILABLE,
    DSP_INITIALIZATION_FAILED,
    MODEL_CONTEXT_INCOMPATIBLE,
    NATIVE_ALLOCATION_FAILED,
    UNSAFE_ARTIFACT,
    PREVIOUS_NATIVE_CRASH,
    CONSTRAINED_DECODING_FAILURE,
    UNKNOWN_NATIVE_ERROR
}

class QnnRuntimeFailure(
    val stage: QnnRuntimeStage,
    val code: QnnFailureCode,
    detail: String,
    cause: Throwable? = null
) : IOException("NPU initialization/operation failed [$code] at ${stage.name}: ${safeQnnDetail(detail)}", cause) {
    val causeStage: QnnRuntimeStage get() = stage
    val errorCode: String get() = code.name
    val nativeDetail: String = safeQnnDetail(detail)
    val suggestedRecoveryType: String get() = when (code) {
        QnnFailureCode.PREVIOUS_NATIVE_CRASH -> "QUALIFIED_RUNTIME_OR_ARTIFACT_CHANGE"
        QnnFailureCode.UNSAFE_ARTIFACT -> "REINSTALL_VERIFIED_ARTIFACT"
        QnnFailureCode.NATIVE_ALLOCATION_FAILED -> "SMALLER_COMPATIBLE_MODEL_OR_CONTEXT"
        QnnFailureCode.CONSTRAINED_DECODING_FAILURE -> "VERIFY_SCHEMA_TOKENIZER_TEMPLATE_AND_SAFE_DECODING_MODE"
        else -> "EXPORT_DIAGNOSTICS_OR_SEPARATE_COMPATIBLE_EDITION"
    }
}

internal fun safeQnnDetail(detail: String): String =
    dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage(detail.take(4096))
        .replace(Regex("""(?:/data/|/storage/|/sdcard/|/home/)[^\s,;]+"""), "[private-path]")
        .replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "[address]")
        .take(640).ifBlank { "native detail unavailable" }

/** Only assign a specific diagnosis when the native/runtime message provides evidence. */
fun classifyQnnFailure(stage: QnnRuntimeStage, error: Throwable): QnnRuntimeFailure {
    if (error is QnnRuntimeFailure) return error
    val detail = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).mapNotNull { it.message?.take(1024) }.joinToString(" ")
    val classification = detail.lowercase()
    if (error is VirtualMachineError || error is ThreadDeath || error is kotlinx.coroutines.CancellationException) throw error
    val code = when {
        isConstrainedDecodingFailure(error) -> QnnFailureCode.CONSTRAINED_DECODING_FAILURE
        "interrupted inside native" in classification || "previous qnn native crash" in classification || "quarantin" in classification -> QnnFailureCode.PREVIOUS_NATIVE_CRASH
        "checksum" in classification || "unsafe artifact" in classification || "truncated" in classification -> QnnFailureCode.UNSAFE_ARTIFACT
        error is LinkageError || classification.contains("dlopen") || classification.contains("undefined symbol") -> QnnFailureCode.ELF_LINK_FAILURE
        classification.contains("qnn system") && classification.contains("version") -> QnnFailureCode.QNN_SYSTEM_VERSION_MISMATCH
        classification.contains("dispatch") && (classification.contains("version") || classification.contains("api")) -> QnnFailureCode.DISPATCH_VERSION_MISMATCH
        classification.contains("skeleton") || classification.contains("skel") || classification.contains("fastrpc") -> QnnFailureCode.DSP_LIBRARY_UNAVAILABLE
        classification.contains("dsp") && (classification.contains("init") || classification.contains("queue")) -> QnnFailureCode.DSP_INITIALIZATION_FAILED
        classification.contains("allocation") || classification.contains("out of memory") -> QnnFailureCode.NATIVE_ALLOCATION_FAILED
        classification.contains("context") && (classification.contains("limit") || classification.contains("exceed")) -> QnnFailureCode.MODEL_CONTEXT_INCOMPATIBLE
        else -> QnnFailureCode.UNKNOWN_NATIVE_ERROR
    }
    val causeStage = when (code) {
        QnnFailureCode.PREVIOUS_NATIVE_CRASH -> QnnRuntimeStage.CRASH_JOURNAL
        QnnFailureCode.UNSAFE_ARTIFACT -> QnnRuntimeStage.MODEL_INTEGRITY
        QnnFailureCode.ELF_LINK_FAILURE -> QnnRuntimeStage.NATIVE_LINK
        QnnFailureCode.DISPATCH_VERSION_MISMATCH -> QnnRuntimeStage.DISPATCH_API
        QnnFailureCode.QNN_SYSTEM_VERSION_MISMATCH -> QnnRuntimeStage.QNN_SYSTEM
        QnnFailureCode.DSP_LIBRARY_UNAVAILABLE, QnnFailureCode.DSP_INITIALIZATION_FAILED -> QnnRuntimeStage.FASTRPC_HTP
        QnnFailureCode.MODEL_CONTEXT_INCOMPATIBLE -> QnnRuntimeStage.MODEL_CONTEXT
        QnnFailureCode.UNKNOWN_NATIVE_ERROR -> if (stage == QnnRuntimeStage.ENGINE_INIT) QnnRuntimeStage.UNKNOWN_WITHIN_NATIVE_INITIALIZE else stage
        else -> stage
    }
    return QnnRuntimeFailure(causeStage, code, detail, error)
}
