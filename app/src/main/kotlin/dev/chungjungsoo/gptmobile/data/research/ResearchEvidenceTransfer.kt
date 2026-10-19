package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Per-capture transport provenance, never catalog equality or URL appearance alone. */
internal fun authorizedResearchTransfer(sessions: List<ResearchSession>, writerAuthority: String?): List<ResearchSnapshot> = sessions.map { session ->
    val sources = session.snapshot.sources.filter { source ->
        session.authority == writerAuthority || source.publicCapture && source.retrievalReceipt.isNotBlank()
    }
    val ids = sources.mapTo(hashSetOf()) { it.id }
    AppLogRecorder.record("CombinedEvidence", "TRANSFER_MANIFEST · runId=${session.runId} · included=${sources.size} · excluded=${session.snapshot.sources.size - sources.size} · exclusionReason=AUTHORITY_NOT_GRANTED · readable=${sources.count { it.readable }}")
    session.snapshot.copy(
        sources = sources,
        claims = session.snapshot.claims.filter { it.sourceId in ids },
        toolEvents = if (session.authority == writerAuthority) session.snapshot.toolEvents else session.snapshot.toolEvents.filter { it.sourceIds.isNotEmpty() && it.sourceIds.all(ids::contains) },
        notes = if (session.authority == writerAuthority) session.snapshot.notes else listOf("Only individually authorized public captures were transferred; restricted run context was omitted")
    )
}
