package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime

internal data class RuntimeBinaryVersions(val liteRt: String, val qairt: String, val genieX: String, val gguf: String)

/** Unrelated runtime upgrades must not clear an artifact/backend's incompatibility quarantine. */
internal enum class RuntimeBinaryScope {
    LITERT,
    LITERT_QNN,
    GGUF,
    GENIEX_GGUF,
    GENIEX_QAIRT;

    fun identity(versions: RuntimeBinaryVersions): String = when (this) {
        LITERT -> "litert:${versions.liteRt}"
        LITERT_QNN -> "litert:${versions.liteRt}|qairt:${versions.qairt}"
        GGUF -> "gguf:${versions.gguf}"
        GENIEX_GGUF -> "geniex-gguf:${versions.genieX}"
        GENIEX_QAIRT -> "geniex-qairt:${versions.genieX}|qairt:${versions.qairt}"
    }

    fun includesLibrary(name: String): Boolean {
        val n = name.lowercase()
        if (n == "libc++_shared.so") return true
        val lite = n.startsWith("liblitert") && !n.contains("qualcomm") || n.startsWith("libynnpack")
        val qnn = n.startsWith("libqnn") || n.contains("qualcomm") || n.contains("adsprpc") || n.contains("cdsprpc")
        val gguf = n.contains("gguf") || n.contains("llama") || n.contains("ggml") || n.contains("mtmd")
        return when (this) {
            LITERT -> lite
            LITERT_QNN -> lite || qnn
            GGUF -> gguf && !n.contains("geniex")
            GENIEX_GGUF -> gguf || n.contains("geniex") || n.contains("hexagon") || n.contains("cdsprpc") || n.contains("adsprpc")
            GENIEX_QAIRT -> n.contains("genie") || qnn
        }
    }

    companion object {
        fun resolve(artifact: ArtifactRuntime?, accelerator: String, visionAccelerator: String?, genieXEnabled: Boolean): RuntimeBinaryScope = when (artifact) {
            ArtifactRuntime.LLAMA_CPP -> GGUF
            ArtifactRuntime.GENIEX_LLAMA_CPP -> if (genieXEnabled) GENIEX_GGUF else GGUF
            ArtifactRuntime.GENIEX_QAIRT, ArtifactRuntime.GENIE_LEGACY -> GENIEX_QAIRT
            else -> if (listOfNotNull(accelerator, visionAccelerator).any { LocalAccelerators.normalize(it) == LocalAccelerators.NPU }) LITERT_QNN else LITERT
        }
    }
}
