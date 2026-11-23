package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.github.GitHubContextFile
import dev.chungjungsoo.gptmobile.data.github.GitHubContextSource
import dev.chungjungsoo.gptmobile.data.github.GitHubContextVariant
import dev.chungjungsoo.gptmobile.data.github.GitHubTaskContextBundle
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class BenchmarkPreparedContext(val prompt: String, val receipt: BenchmarkContextReceipt)

/** Validate before lookup or inference. This projection never accepts hidden-grader fields. */
internal fun benchmarkContextInputs(fixture: JsonObject): Pair<JsonObject, String> {
    val value = fixture["contextInputs"] as? JsonObject ?: error("BENCHMARK_CONTEXT_INPUTS_MISSING")
    check(value.keys == setOf("schema", "sourceIdentityKind", "query", "files", "declaredSourcePaths", "relevanceScope", "sourceSha")) { "BENCHMARK_CONTEXT_INPUTS_INVALID" }
    check(value.contextText("schema") == "benchmark.context-fixture.v1" && value.contextText("sourceIdentityKind") == "FROZEN_FIXTURE_MANIFEST") { "BENCHMARK_CONTEXT_SCHEMA_CHANGED" }
    val digest = benchmarkHash(BenchmarkPhoneGitHubContract.canonical(value))
    check(digest == fixture.contextText("contextManifestDigest")) { "BENCHMARK_CONTEXT_MANIFEST_CHANGED" }
    val rows = value["files"] as? JsonArray ?: error("BENCHMARK_CONTEXT_SOURCES_MISSING")
    check(rows.size in 1..130 && BenchmarkPhoneGitHubContract.canonical(rows).encodeToByteArray().size <= 2 * 1024 * 1024) { "BENCHMARK_CONTEXT_SOURCES_TOO_LARGE" }
    check(value.contextText("sourceSha") == benchmarkHash(BenchmarkPhoneGitHubContract.canonical(rows))) { "BENCHMARK_CONTEXT_REVISION_CHANGED" }
    val names = rows.map { raw ->
        val row = raw as? JsonObject ?: error("BENCHMARK_CONTEXT_SOURCE_INVALID")
        check(row.keys == setOf("path", "blobSha", "content")) { "BENCHMARK_CONTEXT_SOURCE_INVALID" }
        val path = requireNotNull(row.contextText("path"))
        check(path.matches(Regex("[A-Za-z0-9_./-]{1,256}")) && !path.startsWith('/') && path.split('/').none { it in setOf("", ".", "..") }) { "BENCHMARK_CONTEXT_PATH_INVALID" }
        val content = requireNotNull(row.contextText("content"))
        val bytes = content.encodeToByteArray()
        check(bytes.size <= 256 * 1024) { "BENCHMARK_CONTEXT_SOURCE_TOO_LARGE" }
        val blob = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".encodeToByteArray() + bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(row.contextText("blobSha") == blob) { "BENCHMARK_CONTEXT_BLOB_CHANGED" }
        path
    }
    check(names.distinct().size == names.size && names == names.sorted()) { "BENCHMARK_CONTEXT_MANIFEST_AMBIGUOUS" }
    val declared = (value["declaredSourcePaths"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
        ?: error("BENCHMARK_CONTEXT_SOURCE_SCOPE_MISSING")
    check(declared.isNotEmpty() && declared.distinct() == declared && declared.all { it in names }) { "BENCHMARK_CONTEXT_SOURCE_SCOPE_INVALID" }
    check(value.contextText("query")?.encodeToByteArray()?.size in 1..16_384) { "BENCHMARK_CONTEXT_QUERY_INVALID" }
    return value to digest
}

internal fun benchmarkContextSourceIdentity(inputs: JsonObject): BenchmarkContextSourceIdentity {
    val files = inputs.getValue("files") as JsonArray
    val references = JsonArray(files.filter { ((it as JsonObject).contextText("path") ?: "").startsWith("docs/") })
    return BenchmarkContextSourceIdentity(
        requireNotNull(inputs.contextText("sourceSha")),
        benchmarkHash(BenchmarkPhoneGitHubContract.canonical(references)),
        (inputs.getValue("declaredSourcePaths") as JsonArray).size,
        files.size
    )
}

internal suspend fun benchmarkPrepareContext(
    fixture: JsonObject,
    plan: BenchmarkEngineeringPlan,
    taskId: String,
    variant: String,
    localRanker: (suspend (String, Map<String, String>) -> Map<String, Double>?)? = null,
    now: () -> Long = { System.nanoTime() / 1_000_000 }
): BenchmarkPreparedContext {
    val experiment = requireNotNull(plan.contextExperiment)
    require(variant in experiment.variants)
    val (inputs, digest) = benchmarkContextInputs(fixture)
    check(plan.contextManifestDigests[taskId] == digest) { "BENCHMARK_CONTEXT_CHANGED_AFTER_FREEZE" }
    val identity = benchmarkContextSourceIdentity(inputs)
    check(plan.contextSourceIdentities[taskId] == identity) { "BENCHMARK_CONTEXT_SOURCE_CHANGED_AFTER_FREEZE" }
    val rows = (inputs.getValue("files") as JsonArray).map { it as JsonObject }.associateBy { requireNotNull(it.contextText("path")) }
    val files = rows.map { (path, row) -> GitHubContextFile(path, requireNotNull(row.contextText("blobSha")), requireNotNull(row.contextText("content")).encodeToByteArray().size.toLong()) }
    val sourceSha = requireNotNull(inputs.contextText("sourceSha"))
    val query = requireNotNull(inputs.contextText("query"))
    val cache = linkedMapOf<String, GitHubContextSource>()
    var reads = 0
    var hits = 0
    val calls = linkedMapOf<String, Int>()
    suspend fun build(): JsonObject = GitHubTaskContextBundle.build(
        repository = "benchmark/fixture", sourceSha = sourceSha, ref = "frozen-fixture", files = files,
        query = query, variant = GitHubContextVariant.valueOf(variant), budgetBytes = experiment.budgetBytes,
        sourceIdentityKind = "FROZEN_FIXTURE_MANIFEST",
        load = { path ->
            reads++
            calls[path] = (calls[path] ?: 0) + 1
            cache[path]?.copy(cacheHit = true)?.also { hits++ } ?: rows[path]?.let { row ->
                GitHubContextSource(path, requireNotNull(row.contextText("blobSha")), requireNotNull(row.contextText("content"))).also { cache[path] = it }
            }
        },
        localRanker = localRanker
    )
    val coldStarted = now()
    val cold = build()
    val coldMs = (now() - coldStarted).coerceAtLeast(0)
    val coldReads = reads
    val coldCalls = calls.values.sumOf { (it - 1).coerceAtLeast(0) }
    calls.clear()
    val warmStarted = now()
    val warm = build()
    val warmMs = (now() - warmStarted).coerceAtLeast(0)
    check(cold.contextText("source_sha") == sourceSha && warm.contextText("source_sha") == sourceSha) { "BENCHMARK_CONTEXT_REVISION_MISMATCH" }
    // Selection is deterministic for these immutable inputs. Runtime timing/cache
    // receipts can change; only source-bearing sections enter the model request.
    val contextKeys = setOf("features", "selected_ranges", "documents", "test_candidates", "coverage", "variant", "source_sha", "source_identity_kind")
    val selected = JsonObject(warm.filterKeys { it in contextKeys })
    val supplement = if (variant == "CURRENT") "" else "\n\nRepository reference for the same frozen public starting source. These are source observations, not executed test evidence.\n$selected"
    val original = requireNotNull(fixture.contextText("prompt"))
    val prompt = original + supplement
    fun paths(key: String): List<String> = (warm[key] as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonObject)?.contextText("path") ?: (item as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
    val selectedPaths = (paths("selected_ranges") + paths("documents")).toSet()
    val declared = (inputs.getValue("declaredSourcePaths") as JsonArray).map { (it as JsonPrimitive).content }.toSet()
    val semantic = when {
        variant == "CURRENT" -> "CURRENT_BASELINE"
        (warm["semantic_applied"] as? JsonPrimitive)?.booleanOrNull == true -> "LOCAL_SEMANTIC_AND_LEXICAL"
        variant == "SEMANTIC" -> "LEXICAL_FALLBACK"
        else -> warm.contextText("ranking") ?: "UNKNOWN"
    }
    val bytes = supplement.encodeToByteArray().size
    return BenchmarkPreparedContext(
        prompt,
        BenchmarkContextReceipt(
            variant = variant, manifestDigest = digest, sourceSha = sourceSha,
            referenceDigest = identity.referenceDigest, indexDigest = sourceSha,
            promptDigest = benchmarkHash(prompt), selectedPathCount = selectedPaths.size, selectedSourceCount = selectedPaths.intersect(declared).size,
            declaredSourceCount = declared.size, selectedTestCount = paths("test_candidates").distinct().size,
            contextBytes = bytes, estimatedContextTokens = (bytes + 2) / 3,
            coldPreparationMs = coldMs, warmPreparationMs = warmMs, coldSourceReads = coldReads,
            warmSourceReads = reads - coldReads, warmCacheHits = hits,
            repeatedSourceReads = coldCalls + calls.values.sumOf { (it - 1).coerceAtLeast(0) },
            retrievalMode = semantic, revisionVerified = true
        )
    )
}

private fun JsonObject.contextText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
