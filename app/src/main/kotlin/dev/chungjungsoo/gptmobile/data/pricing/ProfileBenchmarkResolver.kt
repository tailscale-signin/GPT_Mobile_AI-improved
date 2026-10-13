package dev.chungjungsoo.gptmobile.data.pricing

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreboard
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardEntry
import dev.chungjungsoo.gptmobile.data.benchmark.ScoreboardKind
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

object ProfileBenchmarkResolver {
    fun personal(profile: PlatformV2, history: List<BenchmarkRun>): ScoreboardEntry? {
        val provider = billingProvider(profile) ?: return null
        if (provider == "ON_DEVICE") return null
        // Legacy client-type-only records cannot certify endpoint identity.
        return choose(profile, history.filter { it.billingOrigin == provider }.mapNotNull(BenchmarkScoreboard::fromRun).mapNotNull(BenchmarkScoreboard::evaluate))
    }
    fun choose(profile: PlatformV2, entries: List<ScoreboardEntry>): ScoreboardEntry? {
        val provider = billingProvider(profile) ?: return null
        val candidates = entries.filter { it.submission.kind == ScoreboardKind.MODEL && it.submission.provider == provider && it.submission.model == BenchmarkScoreboard.usedModel(provider, profile.model) }
        val plan = if (candidates.any { it.submission.plan == BenchmarkMode.FULL }) BenchmarkMode.FULL else BenchmarkMode.QUICK
        return BenchmarkScoreboard.best(candidates.filter { it.submission.plan == plan }.map { it.submission }).firstOrNull()
    }
}
