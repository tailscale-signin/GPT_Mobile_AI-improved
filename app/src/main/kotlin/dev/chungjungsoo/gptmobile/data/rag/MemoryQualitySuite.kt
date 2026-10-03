package dev.chungjungsoo.gptmobile.data.rag

import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeProject
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class MemoryQualitySample(val facts: Int, val correctRetrievals: Int, val queries: Int, val p95RecallMillis: Double, val corpusBuildMillis: Double, val clientPssKiB: Long)

@Serializable
data class MemoryQualityReport(val corpusVersion: Int = 1, val engine: String = "Deterministic extraction + lexical recall", val passedGuards: Int, val totalGuards: Int, val samples: List<MemoryQualitySample>, val measuredAt: Long = System.currentTimeMillis())

/** Fixed synthetic data. Never reads or changes the user's vault, projects or semantic index. */
object MemoryQualitySuite {
    suspend fun run(): MemoryQualityReport = withContext(Dispatchers.Default) {
        val guards = listOf(
            MemoryLearning.extract("My name is Morgan.", 70).any { it.relation.relationType == "NAMED" },
            MemoryLearning.extract("I live in Oslo.", 70).any { it.relation.relationType == "LOCATED_IN" },
            MemoryLearning.extract("Do not remember that I live in Oslo.", 70).isEmpty(),
            MemoryLearning.extract("> My name is Morgan.", 70).isEmpty(),
            MemoryLearning.extract("My password is secret123.", 70).isEmpty(),
            MemoryLearning.extract("Should I live in Oslo?", 70).isEmpty(),
            "artificial intelligence" in RecurringTopicLearning.topics("inteligencia artificial"),
            "artificial intelligence" in RecurringTopicLearning.topics("人工智能"),
            !MemoryScope("project:a", false, KnowledgeProject("a", "A")).accepts("project:b"),
            !MemoryScope("project:a", false, KnowledgeProject("a", "A")).accepts("personal"),
            !MemoryScope(isTemporary = true).accepts("personal")
        )
        val samples = listOf(4096, 16384).map { count ->
            val started = System.nanoTime()
            val facts = (0 until count).map { VaultFact("$it", MemoryLearning.observation("Reference code QZX$it")) }
            val build = (System.nanoTime() - started) / 1_000_000.0
            var correct = 0
            val times = (0 until 20).map { index ->
                currentCoroutineContext().ensureActive()
                val target = (index * 7919) % count
                val before = System.nanoTime()
                val result = MemoryRecallPolicy.rank("QZX$target", facts)
                if (result.map { it.id } == listOf("$target")) correct++
                (System.nanoTime() - before) / 1_000_000.0
            }.sorted()
            MemoryQualitySample(count, correct, times.size, times[(times.size * 0.95).toInt().coerceAtMost(times.lastIndex)], build, android.os.Debug.getPss())
        }
        MemoryQualityReport(passedGuards = guards.count { it }, totalGuards = guards.size, samples = samples)
    }
}
