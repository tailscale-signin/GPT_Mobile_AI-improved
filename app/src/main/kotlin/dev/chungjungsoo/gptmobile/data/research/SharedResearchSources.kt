package dev.chungjungsoo.gptmobile.data.research

/** Merge only already-authorized partitions, binding each claim to its own captured source version. */
internal fun mergeResearchSnapshots(previous: List<ResearchSnapshot>, incoming: ResearchSnapshot): ResearchSnapshot {
    val snapshots = if (isResearchConversationOnly(incoming.task)) listOf(incoming) else previous + incoming

    // A storage checkpoint is not a new evidence revision. In particular, renaming
    // question IDs on the first save would invalidate its already-bound review.
    fun containsEvidence(candidate: ResearchSnapshot, other: ResearchSnapshot) =
        candidate.task == other.task &&
            candidate.sources.containsAll(other.sources) &&
            candidate.claims.containsAll(other.claims) &&
            candidate.questionPlan.containsAll(other.questionPlan) &&
            candidate.questions.containsAll(other.questions)
    if (snapshots.all { containsEvidence(incoming, it) }) {
        val review = incoming.currentReviewRecord() ?: snapshots.asReversed().firstNotNullOfOrNull { snapshot ->
            snapshot.currentReviewRecord()?.takeIf { it.corpusDigest == incoming.evidenceRevisionDigest() }
        }
        return incoming.copy(
            round = snapshots.maxOf { it.round },
            attempts = snapshots.maxOf { it.attempts },
            searches = snapshots.maxOf { it.searches },
            engineResponses = snapshots.maxOf { it.engineResponses },
            engineFailures = snapshots.maxOf { it.engineFailures },
            targetSources = snapshots.maxOf { it.targetSources },
            numericalFactBindingRequired = snapshots.any { it.numericalFactBindingRequired },
            reviewRecord = review ?: incoming.reviewRecord,
            extractedSourceVersions = snapshots.flatMap { it.extractedSourceVersions }.toSet(),
            queries = snapshots.flatMap { it.queries }.distinct(),
            toolEvents = snapshots.flatMap { it.toolEvents }.distinct().sortedBy { it.recordedAt }.takeLast(200)
        )
    }
    val exactSuperset = snapshots.asReversed().firstOrNull { candidate -> snapshots.all { containsEvidence(candidate, it) } }
    if (exactSuperset != null) {
        return incoming.copy(
            round = snapshots.maxOf { it.round },
            attempts = snapshots.maxOf { it.attempts },
            searches = snapshots.maxOf { it.searches },
            engineResponses = snapshots.maxOf { it.engineResponses },
            engineFailures = snapshots.maxOf { it.engineFailures },
            targetSources = snapshots.maxOf { it.targetSources },
            numericalFactBindingRequired = snapshots.any { it.numericalFactBindingRequired },
            sources = exactSuperset.sources,
            claims = exactSuperset.claims,
            questionPlan = exactSuperset.questionPlan,
            questions = exactSuperset.questions,
            reviewRecord = exactSuperset.currentReviewRecord(),
            extractedSourceVersions = snapshots.flatMap { it.extractedSourceVersions }.toSet(),
            queries = snapshots.flatMap { it.queries }.distinct(),
            notes = snapshots.flatMap { it.notes }.distinct(),
            toolEvents = snapshots.flatMap { it.toolEvents }.distinct().sortedBy { it.recordedAt }.takeLast(200)
        )
    }
    fun workIdentity(source: ResearchSource) = source.equivalenceGroup.ifBlank { source.canonicalUrl.ifBlank { source.url } }
    fun capturedIdentity(source: ResearchSource) = workIdentity(source) + ":" + capturedResearchVersion(source)
    val allSources = snapshots.flatMap { it.sources }
    val readableCaptures = allSources.filter { it.readable }.groupBy { it.id to workIdentity(it) }
    fun identity(source: ResearchSource): String {
        // An unversioned failed retry did not capture an empty new version. It can
        // annotate one known capture, but ambiguity must never choose a version.
        if (source.versionId.isBlank() && source.passage.isBlank() && source.status in setOf("Failed", "Blocked")) {
            val captures = readableCaptures[source.id to workIdentity(source)].orEmpty().map(::capturedIdentity).distinct()
            if (captures.size == 1) return captures.single()
        }
        return capturedIdentity(source)
    }
    val groups = allSources.groupBy(::identity)
    val identitiesPerId = allSources.groupBy { it.id }.mapValues { (_, values) -> values.map(::identity).distinct().size }
    val sourcesByIdentity = groups.mapValues { (key, versions) ->
        val best = versions.maxWith(compareBy<ResearchSource> { it.readable }.thenBy { it.retrievalQuality == "FULL_TEXT" }.thenBy { it.status == "Read" }.thenBy { it.bodyChunks.size }.thenBy { it.passage.length }.thenBy { it.retrievedAt })
        best.copy(
            id = if (identitiesPerId.getValue(best.id) > 1) "S" + researchHash(key).take(16) else best.id,
            engines = versions.flatMap { it.engines }.distinct().take(20),
            discoveredByProfiles = versions.flatMap { it.discoveredByProfiles }.toSet(),
            readByProfiles = versions.flatMap { it.readByProfiles }.toSet(),
            providerReceipts = versions.flatMap { it.providerReceipts }.distinct(),
            failureReason = versions.lastOrNull { !it.readable && it.failureReason.isNotBlank() }?.failureReason ?: best.failureReason
        )
    }
    fun questionIdentity(question: ResearchQuestion) = "Q" + researchHash(question.copy(id = "", text = question.text.trim(), evidenceNeeded = question.evidenceNeeded.trim()).json().toString()).take(16)
    val questions = snapshots.flatMap { it.questionPlan }.map { it.copy(id = questionIdentity(it)) }.distinctBy { it.id }
    val claims = snapshots.flatMap { snapshot ->
        val questionIds = snapshot.questionPlan.associate { it.id to questionIdentity(it) }
        snapshot.claims.mapNotNull { claim ->
            val original = snapshot.sources.firstOrNull { researchClaimMatchesCapture(claim, it) } ?: return@mapNotNull null
            val retained = sourcesByIdentity[identity(original)] ?: return@mapNotNull null
            val remapped = claim.copy(sourceId = retained.id, questionId = questionIds[claim.questionId] ?: claim.questionId, facts = claim.facts.map { it.copy(sourceId = retained.id) })
            if (!researchClaimMatchesCapture(remapped, retained)) return@mapNotNull null
            remapped
        }
    }.groupBy { listOf(it.sourceId, it.text, it.quote, it.questionId, it.facts) }.values.map { revisions ->
        // A verdict updates the same exact captured claim; it is not another
        // finding. Keep a real review when a late extraction is still unreviewed.
        revisions.withIndex().maxWith(
            compareBy<IndexedValue<ResearchClaim>> { it.value.numericalFactBindingRequired }
                .thenBy { it.value.verdict in setOf("Supported", "Contradicted", "Insufficient") }
                .thenBy { it.value.independentReview }
                .thenBy { it.value.exactQuoteVerified }
                .thenBy { it.value.reviewInvocationId.isNotBlank() }
                .thenBy { it.index }
        ).value
    }
    return incoming.copy(
        round = snapshots.maxOf { it.round },
        attempts = snapshots.maxOf { it.attempts },
        searches = snapshots.maxOf { it.searches },
        engineResponses = snapshots.maxOf { it.engineResponses },
        engineFailures = snapshots.maxOf { it.engineFailures },
        targetSources = snapshots.maxOf { it.targetSources },
        numericalFactBindingRequired = snapshots.any { it.numericalFactBindingRequired },
        extractedSourceVersions = snapshots.flatMap { it.extractedSourceVersions }.toSet(),
        sources = sourcesByIdentity.values.toList(),
        claims = claims,
        questionPlan = questions.ifEmpty { incoming.questionPlan },
        questions = questions.map { it.text }.ifEmpty { snapshots.flatMap { it.questions }.distinct() },
        queries = snapshots.flatMap { it.queries }.distinct(),
        toolEvents = snapshots.flatMap { it.toolEvents }.distinct().sortedBy { it.recordedAt }.takeLast(200)
    )
}
