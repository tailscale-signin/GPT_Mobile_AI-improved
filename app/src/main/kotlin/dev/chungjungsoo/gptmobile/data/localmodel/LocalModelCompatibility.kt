package dev.chungjungsoo.gptmobile.data.localmodel

/** Artifact-specific findings, separate from filename/SoC compatibility checks. */
object LocalModelCompatibility {
    private const val MINICPM_REPO = "Tdamre/MiniCPM5-1B-litert-lm"
    private val unsupportedMiniCpmFiles = setOf(
        "MiniCPM5-1B-qualcomm-sm8750.litertlm",
        "MiniCPM5-1B-qualcomm-sm8750-c1024.litertlm"
    )
    private val gemma4QnnFiles = setOf(
        "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
        "gemma-4-E2B-it_qualcomm_qcs8275.litertlm"
    )

    const val RECORDS_VERSION = 2

    // Admission and qualification are different: Gemma 4 may attempt isolated
    // execution without claiming that its device/runtime tuple has passed testing.
    val records: List<ModelCompatibilityRecord> = listOf(
        ModelCompatibilityRecord(
            "gemma4-qnn-dsp-queue",
            2,
            "UNVERIFIED",
            gemma4QnnFiles,
            evidence = "Prior QNN DSP queue failure reported; filename-wide block removed. Execution on the current artifact/runtime/device tuple remains unverified.",
            correctiveAction = "Run the SoC-matched NPU export in the isolated worker with a short text prompt and capture initialization and output evidence."
        ),
        ModelCompatibilityRecord(
            "minicpm5-qnn-allocation",
            1,
            "BLOCKED",
            unsupportedMiniCpmFiles,
            evidence = "Publisher reports QNN PD allocation failure",
            correctiveAction = "Download MiniCPM5-1B-web.litertlm or another matching NPU model."
        )
    )

    fun exactQualification(identity: ModelCompatibilityIdentity?): ModelCompatibilityRecord? =
        records.firstOrNull { it.qualifies(identity) }

    // Publisher's Android results: both AOT exports fail QNN PD allocation (8.62
    // and 5.50 GiB). Matching SM8750 alone does not make them runnable.
    // https://huggingface.co/Tdamre/MiniCPM5-1B-litert-lm/blob/main/README.md
    fun unsupportedReason(repoId: String, path: String): String? {
        val fileName = path.substringAfterLast('/')
        // Gemma 4 is admitted here for catalog, imported and legacy rows. Actual
        // SoC, package, library and native-crash checks belong to the runtime.
        if (unsupportedMiniCpmFiles.none { it.equals(fileName, true) }) {
            return null
        }
        return "This MiniCPM NPU export has a documented QNN memory allocation failure. " +
            "Download MiniCPM5-1B-web.litertlm from the same repository and use GPU or CPU, or choose another NPU model."
    }

    fun installedPackageIssue(downloadUrl: String, path: String, identity: ModelCompatibilityIdentity? = null): String? {
        if (exactQualification(identity) != null) return null
        val repoId = downloadUrl.removePrefix("https://huggingface.co/").substringBefore("/resolve/")
        return unsupportedReason(repoId, path)
    }

    // Despite its name, this specific export passed Android CPU/GPU inference.
    // Keep the exception scoped to the publisher and artifact, not all "web" files.
    fun validatedContextTokens(repoId: String, path: String): Int? =
        2048.takeIf { repoId.equals(MINICPM_REPO, true) && path == "MiniCPM5-1B-web.litertlm" }
}
