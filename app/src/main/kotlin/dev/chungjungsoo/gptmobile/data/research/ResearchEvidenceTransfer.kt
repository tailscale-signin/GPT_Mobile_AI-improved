package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Per-capture transport provenance, never catalog equality or URL appearance alone. */
internal fun authorizedResearchTransfer(sessions: List<ResearchSession>, writerAuthority: String?): List<ResearchSnapshot> = sessions.map { session ->
    val sameAuthority = session.authority == writerAuthority
    val sources = session.snapshot.sources.filter { source ->
        sameAuthority || source.publicCapture && source.retrievalReceipt.isNotBlank()
    }
    AppLogRecorder.record("CombinedEvidence", "TRANSFER_MANIFEST · runId=${session.runId} · included=${sources.size} · excluded=${session.snapshot.sources.size - sources.size} · exclusionReason=AUTHORITY_NOT_GRANTED · readable=${sources.count { it.readable }}")
    if (sameAuthority) return@map session.snapshot
    // Callers bind the task to the accepted user turn before reaching this boundary.
    // A public retrieval authorizes its captured source, not a worker's derived
    // questions, claims, queries, review, tool history or delegated execution plan.
    // Construct a projection so newly added snapshot fields stay private by default.
    // Keep spent-work counters: sharing evidence cannot reset an existing budget.
    val original = session.snapshot
    ResearchSnapshot(
        task = original.task,
        phase = "Shared public evidence",
        round = original.round,
        searches = original.searches,
        attempts = original.attempts,
        engineResponses = original.engineResponses,
        engineFailures = original.engineFailures,
        sources = sources,
        planningStatus = "Destination planning required",
        notes = listOf("Only individually authorized public captures were transferred; restricted run context was omitted. Analyze the retained captures within the destination's authority before claiming completion."),
        updatedAt = original.updatedAt,
        targetSources = original.targetSources,
        taskId = original.taskId,
        rootWorkId = original.rootWorkId,
        originatingUserMessageId = original.originatingUserMessageId,
        taskChatId = original.taskChatId,
        numericalFactBindingRequired = original.numericalFactBindingRequired,
        reportStructure = original.reportStructure
    )
}
