package dev.chungjungsoo.gptmobile.data.agent

/** A retry owns new output while the accepted input snapshot stays byte-for-byte immutable. */
internal fun CombinedInputSnapshot.liveRevisionId(runId: String): String {
    require(runId.isNotBlank()) { "A Combined revision requires a generation identity." }
    return if (resumeFromRunId == null && revisionId.isNotBlank()) {
        revisionId
    } else {
        combinedDigest("combined-output-revision:$runId")
    }
}
