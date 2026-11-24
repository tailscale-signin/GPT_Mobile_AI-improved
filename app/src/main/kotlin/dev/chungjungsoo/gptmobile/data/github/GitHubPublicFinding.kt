package dev.chungjungsoo.gptmobile.data.github

/** All fields come from the admitted turn and a source-owned observation. Model prose never constructs a fact. */
internal data class GitHubPublicFinding(
    val scopeId: String,
    val criterionId: String,
    val subjectId: String,
    val revisionId: String,
    val value: String,
    val evidenceId: String
)

internal enum class GitHubFindingChange { FINDING, CORRECTION, SUPPRESSED }

internal class GitHubPublicFindingLedger(val scopeId: String) {
    private val latest = linkedMapOf<Pair<String, String>, GitHubPublicFinding>()
    private val replaced = linkedSetOf<Triple<String, String, String>>()

    private val cursors = linkedMapOf<String, Int>()
    var closed = false
        private set

    fun finish() {
        closed = true
    }

    fun acceptSequence(job: String, sequence: Int?): Boolean {
        if (closed) return false
        if (sequence == null) return true
        if (cursors[job]?.let { sequence <= it } == true) return false
        cursors[job] = sequence
        while (cursors.size > 128) cursors.remove(cursors.keys.first())
        return true
    }

    fun observe(fact: GitHubPublicFinding): GitHubFindingChange {
        if (closed ||
            fact.scopeId != scopeId ||
            fact.criterionId.isBlank() ||
            fact.subjectId.isBlank() ||
            fact.revisionId.isBlank() ||
            fact.value.isBlank() ||
            fact.evidenceId.isBlank()
        ) {
            return GitHubFindingChange.SUPPRESSED
        }
        val key = fact.criterionId to fact.subjectId
        if (Triple(key.first, key.second, fact.revisionId) in replaced) return GitHubFindingChange.SUPPRESSED
        val previous = latest[key]
        if (previous?.revisionId == fact.revisionId && previous.value == fact.value) return GitHubFindingChange.SUPPRESSED
        if (previous != null && previous.revisionId != fact.revisionId) {
            replaced += Triple(key.first, key.second, previous.revisionId)
            while (replaced.size > 512) replaced.remove(replaced.first())
        }
        latest[key] = fact
        while (latest.size > 256) latest.remove(latest.keys.first())
        return if (previous == null) GitHubFindingChange.FINDING else GitHubFindingChange.CORRECTION
    }
}
