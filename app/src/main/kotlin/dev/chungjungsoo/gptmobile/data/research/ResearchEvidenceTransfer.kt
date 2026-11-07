package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Per-capture transport provenance, never catalog equality or URL appearance alone. */
internal fun authorizedResearchTransfer(sessions: List<ResearchSession>, writerAuthority: String?): List<ResearchSnapshot> = sessions.map { session ->
    val sameAuthority = session.authority == writerAuthority
    val sources = if (sameAuthority) {
        session.snapshot.sources
    } else {
        session.snapshot.sources.filter { it.publicCapture && it.retrievalReceipt.isNotBlank() }.map(::publicCaptureProjection)
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
        notes = listOf(
            "Shared text is an unreviewed excerpt. Original titles, bibliography, reader metadata and archived full text were omitted.",
            "Only individually authorized public captures were transferred; restricted run context was omitted. Analyze the retained captures within the destination's authority before claiming completion. Source labels use public URLs."
        ),
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

/** A public reread can leave metadata from an earlier restricted discovery on the same source. */
private fun publicCaptureProjection(source: ResearchSource): ResearchSource {
    val status = when {
        source.readable -> "Partially read"
        source.retrievalQuality == "ABSTRACT" && source.passage.isNotBlank() -> "Abstract"
        source.status in setOf("Blocked", "Failed", "Deferred") -> source.status
        else -> "Discovered"
    }
    val bodyHash = researchHash(source.passage)
    // Constructor defaults rebuild identity from this public URL and text. They
    // cannot inherit a private title hash, bibliography, version, locator or archive.
    return ResearchSource(
        id = source.id,
        url = source.url,
        title = source.url,
        status = status,
        passage = source.passage,
        depth = source.depth,
        retrievedAt = source.retrievedAt,
        fingerprint = bodyHash,
        location = "Shared public excerpt; original location not transferred",
        retrievalQuality = when (status) {
            "Partially read" -> "PARTIAL_TEXT"
            "Abstract" -> "ABSTRACT"
            "Blocked" -> "BLOCKED"
            "Failed" -> "EMPTY"
            else -> "LEAD"
        },
        versionId = bodyHash.takeIf { source.passage.isNotBlank() }.orEmpty(),
        publicCapture = true,
        retrievalReceipt = source.retrievalReceipt,
        metadataProvenance = listOf(publicCaptureProjectionBinding(source.url, source.retrievalReceipt, bodyHash))
    )
}

/** Enables passage relevance only for an unchanged, explicitly projected public capture. */
internal fun ResearchSource.hasPublicCaptureProjection(): Boolean {
    if (!publicCapture || retrievalReceipt.isBlank() || passage.isBlank()) return false
    val binding = metadataProvenance.singleOrNull()?.takeIf { it.startsWith(PUBLIC_CAPTURE_PROJECTION_PREFIX) } ?: return false
    val bodyHash = researchHash(passage)
    return versionId == bodyHash &&
        fingerprint == bodyHash &&
        binding == publicCaptureProjectionBinding(url, retrievalReceipt, bodyHash)
}

private fun publicCaptureProjectionBinding(url: String, receipt: String, bodyHash: String): String =
    PUBLIC_CAPTURE_PROJECTION_PREFIX + researchHash("$url\u0000$receipt\u0000$bodyHash")

private const val PUBLIC_CAPTURE_PROJECTION_PREFIX = "public_capture_projection_v1:"
