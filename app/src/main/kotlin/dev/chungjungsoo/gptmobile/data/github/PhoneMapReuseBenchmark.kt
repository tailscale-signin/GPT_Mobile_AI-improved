package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Exercises the phone's actual map and LRU with deterministic GitHub responses and isolated, in-memory accounts. */
internal object PhoneMapReuseBenchmark {
    const val TRIALS = 20

    data class Result(
        val scenario: String,
        val trials: Int,
        val passed: Int,
        val commitReads: Int,
        val treeReads: Int,
        val blobReads: Int,
        val p95Nanos: Long,
        val maxCacheChargeBytes: Int
    )

    private enum class Scenario(val title: String, val account: String, val advanced: Boolean, val trees: Int, val blobs: Int) {
        COLD("First chat", "first", false, 1, 2),
        SHARED("Second chat", "first", false, 0, 0),
        ADVANCE("Branch advanced", "first", true, 1, 1),
        SWITCH("Account switched", "second", true, 1, 2),
        EVICTED("After LRU eviction", "first", true, 1, 2)
    }

    private data class Observation(val elapsed: Long, val charge: Int, val commits: Int, val trees: Int, val blobs: Int, val correct: Boolean)

    suspend fun run(trials: Int = TRIALS): List<Result> = withContext(Dispatchers.Default) {
        require(trials in 1..100)
        val observations = Scenario.entries.associateWith { mutableListOf<Observation>() }
        repeat(trials) {
            val store = GitHubRepositoryStateStore()
            for (scenario in Scenario.entries) {
                if (scenario == Scenario.EVICTED) {
                    // Real memory-store eviction. Fill the 256-entry LRU with unrelated records.
                    val pressure = buildJsonObject { put("fixture", true) }
                    repeat(257) { index -> store.write("benchmark-pressure", "row-$index", pressure) }
                }
                observations.getValue(scenario).add(measure(scenario, store))
            }
        }
        observations.map { (scenario, samples) ->
            Result(
                scenario.title,
                trials,
                samples.count { it.correct },
                samples.maxOf { it.commits },
                samples.maxOf { it.trees },
                samples.maxOf { it.blobs },
                samples.map { it.elapsed }.sorted()[ceil(.95 * trials).toInt() - 1],
                samples.maxOf { it.charge }
            )
        }
    }

    private suspend fun measure(scenario: Scenario, store: GitHubRepositoryStateStore): Observation {
        val transport = MapFixture(scenario.advanced)
        val session = GitHubRepositorySession(
            scenario.account,
            "connection",
            "chat-${scenario.name}",
            "task-${scenario.name}",
            store,
            transport::read,
            { _, _ -> error("Unexpected write during a map benchmark") }
        )
        val started = System.nanoTime()
        val selected = session.resolve("fixture", "map", buildJsonObject { put("ref", "main") }, selectContext = true)
        val args = buildJsonObject {
            put("ref", selected.ref)
            put("commit_sha", selected.commitSha)
            put("action", "index")
        }
        session.repositoryMap("fixture", "map", args)
        val result = session.repositoryMap("fixture", "map", JsonObject(args + ("action" to JsonPrimitive("architecture"))))
        val elapsed = System.nanoTime() - started
        val graph = result.getValue("graph").jsonObject
        val names = result.getValue("nodes").jsonArray.map { it.jsonObject["name"] }
        val expected = if (scenario.advanced) "current_value" else "previous_value"
        val stale = if (scenario.advanced) "previous_value" else "current_value"
        val correct = graph["commit_sha"] == JsonPrimitive(transport.head) &&
            graph["complete"] == JsonPrimitive(true) &&
            JsonPrimitive("shared_value") in names &&
            JsonPrimitive(expected) in names &&
            JsonPrimitive(stale) !in names &&
            transport.commits.get() == 1 &&
            transport.trees.get() == scenario.trees &&
            transport.blobs.get() == scenario.blobs
        return Observation(elapsed, store.cachedMemoryCharge(), transport.commits.get(), transport.trees.get(), transport.blobs.get(), correct)
    }

    private class MapFixture(advanced: Boolean) {
        val head = (if (advanced) "c" else "a").repeat(40)
        private val tree = (if (advanced) "d" else "b").repeat(40)
        private val sources = mapOf(
            "shared.py" to "def shared_value():\n    return 1\n",
            "service.py" to "def ${if (advanced) "current_value" else "previous_value"}():\n    return 2\n"
        )
        private val shas = sources.mapValues { (_, text) -> GitHubCommitReconciliation.blobSha(text) }
        private val commitResponse = buildJsonObject {
            put("sha", head)
            put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
        }
        private val treeResponse = buildJsonObject {
            put("sha", tree)
            put("truncated", false)
            put(
                "tree",
                buildJsonArray {
                    sources.forEach { (name, source) ->
                        add(
                            buildJsonObject {
                                put("path", name)
                                put("type", "blob")
                                put("mode", "100644")
                                put("size", source.encodeToByteArray().size)
                                put("sha", shas.getValue(name))
                            }
                        )
                    }
                }
            )
        }
        private val blobsBySha = sources.entries.associate { (name, source) ->
            val sha = shas.getValue(name)
            sha to buildJsonObject {
                put("sha", sha)
                put("encoding", "base64")
                put("content", Base64.getEncoder().encodeToString(source.encodeToByteArray()))
            }
        }
        val commits = AtomicInteger()
        val trees = AtomicInteger()
        val blobs = AtomicInteger()

        fun read(path: String): JsonObject {
            val prefix = "/repos/fixture/map"
            return when {
                path == "$prefix/commits/main" || path == "$prefix/commits/$head" -> {
                    commits.incrementAndGet()
                    commitResponse
                }
                path == "$prefix/git/trees/$tree?recursive=1" -> {
                    trees.incrementAndGet()
                    treeResponse
                }
                path.startsWith("$prefix/git/blobs/") -> {
                    blobs.incrementAndGet()
                    blobsBySha.getValue(path.substringAfterLast('/'))
                }
                else -> error("Unexpected GitHub map request: $path")
            }
        }
    }
}
