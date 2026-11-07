package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.authorizedResearchTransfer
import javax.inject.Inject

internal class AppAuditResearchProbeSource @Inject constructor() :
    AppAuditFixtureSource(
        "research_contract",
        listOf(
            AppAuditFixture("authority_projection_v1", "Public research transfer excludes private context", listOf("research", "auth_and_security")) {
                val privateMarker = "app-audit-private-canary"
                val public = ResearchSource("S1", "https://example.org/audit", "Fixture study", "Read", "A captured public fixture passage.", publicCapture = true, retrievalReceipt = "audit-read")
                val private = public.copy(id = "S2", publicCapture = false, passage = privateMarker)
                val snapshot = ResearchSnapshot("Fixture task", round = 3, attempts = 12, searches = 8, sources = listOf(public, private), queries = listOf(privateMarker), notes = listOf(privateMarker), complete = true)
                val session = ResearchSession("fixture-run", 1, snapshot, authority = "fixture-authority-a")
                val shared = authorizedResearchTransfer(listOf(session), "fixture-authority-b").single()
                verify(shared.sources.map { it.id } == listOf(public.id))
                verify(shared.sources.single().passage == public.passage && shared.sources.single().retrievalReceipt == public.retrievalReceipt)
                verify(!shared.json().toString().contains(privateMarker) && !shared.complete)
                verify(shared.round == 3 && shared.attempts == 12 && shared.searches == 8)
                verify(authorizedResearchTransfer(listOf(session), "fixture-authority-a").single() === snapshot)
            }
        )
    )
