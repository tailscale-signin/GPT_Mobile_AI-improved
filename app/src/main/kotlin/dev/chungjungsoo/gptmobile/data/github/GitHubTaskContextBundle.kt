package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

@Serializable
enum class GitHubContextVariant { CURRENT, REFERENCE, TASK, SEMANTIC }

data class GitHubContextFile(val path: String, val blobSha: String, val size: Long = 0, val symbols: String = "")
data class GitHubContextSource(val path: String, val blobSha: String, val text: String, val cacheHit: Boolean = false)

/** Bounded, source-pinned retrieval. Documents and commands are data; this class never executes them. */
object GitHubTaskContextBundle {
    const val SCHEMA = "repository.task_context.v1"
    const val VERSION = 3
    const val FEATURE_MAP = "docs/architecture/feature-map.json"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private val sha = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
    private val ignoredTerms = setOf("the", "this", "that", "with", "from", "and", "for", "please", "fix", "improve", "implement", "feature", "code", "file", "files", "github", "repo", "repository", "all", "can", "how", "what", "does", "should", "would")

    suspend fun build(
        repository: String,
        sourceSha: String,
        ref: String,
        files: List<GitHubContextFile>,
        query: String,
        variant: GitHubContextVariant = GitHubContextVariant.TASK,
        budgetBytes: Int = 12_000,
        treeComplete: Boolean = true,
        load: suspend (String) -> GitHubContextSource?,
        localRanker: (suspend (String, Map<String, String>) -> Map<String, Double>?)? = null,
        sourceIdentityKind: String = "GIT_COMMIT",
        indexedFiles: Int? = null,
        localRankingFallback: String? = null
    ): JsonObject {
        require(repository.length <= 201 && repository.split('/').let { it.size == 2 && it.all(String::isNotBlank) }) { "Repository context requires owner/name." }
        require(sourceIdentityKind in setOf("GIT_COMMIT", "FROZEN_FIXTURE_MANIFEST"))
        val sourcePattern = if (sourceIdentityKind == "GIT_COMMIT") Regex("[a-fA-F0-9]{40}") else Regex("[a-fA-F0-9]{64}")
        require(sourceSha.matches(sourcePattern)) { "Repository context requires an immutable identity matching its source kind." }
        val started = System.nanoTime()
        val budget = budgetBytes.coerceIn(4096, 64_000)
        val accepted = files.take(20_000).filter { validPath(it.path) && it.blobSha.matches(sha) && it.size in 0..MAX_FILE_BYTES.toLong() }
            .distinctBy { it.path }.associateBy { it.path }
        val queryTerms = terms(query.take(6000))
        val loaded = linkedMapOf<String, GitHubContextSource>()
        val missing = linkedSetOf<String>()
        var attempted = 0
        var unavailableCount = 0
        suspend fun source(path: String): GitHubContextSource? {
            loaded[path]?.let { return it }
            val entry = accepted[path] ?: return null
            if (attempted >= 20) {
                unavailableCount++
                return null
            }
            currentCoroutineContext().ensureActive()
            attempted++
            val value = try {
                load(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Identity/authorization errors remain hard failures. Only a missing selected
                // immutable source can be represented as absent evidence by the loader.
                throw failure
            }
            if (value == null) {
                missing += path
                unavailableCount++
                return null
            }
            require(value.path == path && value.blobSha.equals(entry.blobSha, true)) { "Repository context source identity changed." }
            require(value.text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Repository context source exceeds the read bound." }
            loaded[path] = value
            return value
        }
        var manifest: JsonObject? = null
        var manifestStatus = "ABSENT"
        val selectedFeatures = mutableListOf<JsonObject>()
        val featurePaths = linkedSetOf<String>()
        val featureTests = linkedSetOf<String>()
        val featureDocuments = linkedSetOf<String>()
        val staleManifestPaths = linkedSetOf<String>()
        if (variant != GitHubContextVariant.CURRENT && accepted[FEATURE_MAP]?.size?.let { it <= 256 * 1024 } == true) {
            val raw = source(FEATURE_MAP)
            manifest = raw?.text?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 256 * 1024 }?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            }?.takeIf { it.number("schema_version") == 1 }
            manifestStatus = if (manifest == null) "INVALID_OR_UNAVAILABLE" else "VALID"
            val fileBlobs = manifest?.get("file_blobs") as? JsonObject
            fun verifiedPath(path: String): Boolean {
                val actual = accepted[path]
                if (actual == null) {
                    staleManifestPaths += path
                    return false
                }
                val declared = (fileBlobs?.get(path) as? JsonPrimitive)?.contentOrNull
                    ?: (fileBlobs?.get(path) as? JsonObject)?.text("blob_sha")?.takeIf(String::isNotBlank)
                if ((fileBlobs?.containsKey(path) == true && declared == null) || (declared != null && !declared.equals(actual.blobSha, true))) {
                    staleManifestPaths += path
                    return false
                }
                return true
            }
            val rankedFeatures = manifest?.objects("features").orEmpty().take(128).map { feature ->
                val names = (listOf(feature.text("id"), feature.text("title")) + feature.strings("terms").take(40)).joinToString(" ")
                val score = terms(names).intersect(queryTerms).size * 10 + feature.strings("paths").count { query.contains(it, true) } * 100
                feature to score
            }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<JsonObject, Int>> { it.second }.thenBy { it.first.text("id") }).take(4)
            for ((feature, _) in rankedFeatures) {
                val references = (feature.strings("paths") + feature.strings("tests") + feature.strings("documents")).distinct()
                val verified = references.take(256).associateWith(::verifiedPath)
                val freshness = when {
                    references.size > 256 -> "REFERENCE_LIMIT_REACHED"
                    verified.values.any { !it } -> "STALE_OR_MISSING_REFERENCES"
                    references.isEmpty() || references.any { fileBlobs?.containsKey(it) != true } -> "BLOB_BINDING_UNAVAILABLE"
                    else -> "REFERENCED_BLOBS_VERIFIED"
                }
                featurePaths += feature.strings("paths").take(32).filter { verified[it] == true }
                featureTests += feature.strings("tests").take(24).filter { verified[it] == true }
                featureDocuments += feature.strings("documents").take(8).filter { it.endsWith(".md", true) && verified[it] == true }
                selectedFeatures += buildJsonObject {
                    put("id", feature.text("id").take(120))
                    put("title", feature.text("title").take(200))
                    put("freshness", freshness)
                    put("invariants", json(if (freshness == "REFERENCED_BLOBS_VERIFIED") feature.strings("invariants").take(6).map { it.take(300) } else emptyList()))
                    put("commands", json(if (freshness == "REFERENCED_BLOBS_VERIFIED") feature.strings("commands").take(6).map { it.take(300) } else emptyList()))
                    put("commands_executed", false)
                }
            }
        }
        val documents = mutableListOf<JsonObject>()
        val selectedRanges = mutableListOf<JsonObject>()
        val wiring = mutableListOf<JsonObject>()
        var semanticApplied = false
        val graphCandidates = linkedSetOf<String>()
        if (variant in setOf(GitHubContextVariant.TASK, GitHubContextVariant.SEMANTIC)) {
            val rankedPaths = accepted.values.asSequence().filter { it.path != FEATURE_MAP && !it.path.endsWith(".md", true) }
                .map { entry ->
                    val pathTerms = terms(entry.path)
                    val score = pathTerms.intersect(queryTerms).size * 10 + terms(entry.symbols, 2048).intersect(queryTerms).size * 15 +
                        (if (query.contains(entry.path, true)) 200 else 0) +
                        (if (entry.path in featurePaths) 80 else 0) +
                        (if (entry.path in featureTests) 50 else 0)
                    entry.path to score
                }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(12).toList()
            // Reserve related test slots even when implementation files dominate path ranking.
            // The loader shares one deadline. Hydrate an implementation and its test
            // before optional references or more implementation candidates consume it.
            val implementation = rankedPaths.firstOrNull { it.first !in featureTests }?.first ?: rankedPaths.firstOrNull()?.first
            val primary = (listOfNotNull(implementation) + featureTests.take(1) + rankedPaths.take(6).map { it.first } + featureTests.take(2)).distinct().take(8)
            for (path in primary) source(path)
            val sources = loaded.filterKeys { it in primary }.mapValues { it.value.text }
            val graph = GitHubCodeContext.build(sources, accepted.keys.toList(), sourceSha)
            graphCandidates += graph.objects("suggested_ranges").map { it.text("path") }
            val relatedTests = primary.flatMap { GitHubSearchContext.related(it, accepted.keys.toList()).mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull } }
            featureTests += relatedTests
            for (path in (featureTests + graphCandidates).filter { it !in primary }.take(3)) source(path)
            val candidateSources = loaded.filter { (path, source) -> path != FEATURE_MAP && !path.endsWith(".md", true) && source.text.isNotEmpty() }
            val candidateRanges = candidateSources.mapValues { (path, source) ->
                val reason = when (path) {
                    in featureTests -> "FEATURE_TEST"
                    in featurePaths -> "FEATURE_REFERENCE"
                    in graphCandidates -> "LEXICAL_DEPENDENCY"
                    else -> "TASK_PATH_MATCH"
                }
                sourceRange(source, queryTerms, query.take(6000), reason)
            }
            val semanticScores = (
                if (variant == GitHubContextVariant.SEMANTIC && localRanker != null && candidateSources.isNotEmpty()) {
                    localRanker(
                        query.take(6000),
                        candidateRanges.mapValues { (path, range) ->
                            "$path\nblob_sha=${range.text("blob_sha")} lines=${range.number("start_line")}-${range.number("end_line")}\n${range.text("excerpt") }"
                        }
                    )
                } else {
                    null
                }
                )?.filter { (path, score) -> path in candidateSources && score.isFinite() }
            semanticApplied = !semanticScores.isNullOrEmpty()
            val orderedSources = candidateSources.values.sortedByDescending { value ->
                (semanticScores?.get(value.path)?.takeIf { it.isFinite() } ?: 0.0) * 10 +
                    terms(value.path).intersect(queryTerms).size + (if (value.path in featurePaths) 8 else 0)
            }
            for (value in orderedSources) {
                selectedRanges += candidateRanges.getValue(value.path)
                wiring += wiringCandidates(value, queryTerms)
            }
        }
        // Source windows take precedence for TASK/SEMANTIC; REFERENCE retains its
        // document-only behavior. No additional calls or deadline are granted.
        if (variant != GitHubContextVariant.CURRENT) {
            val defaults = listOf("docs/architecture/repository-guide.md", "AGENTS.md", "README.md")
            val docPaths = (featureDocuments + defaults.filter { it in accepted }).take(4)
            for (path in docPaths) {
                val value = source(path) ?: continue
                if (value.text.isBlank()) continue
                documents += documentSections(value, queryTerms).take(2)
            }
        }
        val sourceCandidates = missing.filter { it != FEATURE_MAP && !it.endsWith(".md", true) }.take(8).map { path ->
            buildJsonObject {
                put("path", path)
                put("blob_sha", accepted.getValue(path).blobSha)
                put("source_loaded", false)
            }
        }.toMutableList()
        val knownTests = featureTests.filter { it in accepted }.take(16).toMutableList()
        val referenceDigest = digest(loaded.filterKeys { it == FEATURE_MAP || it.endsWith(".md", true) }.values.joinToString("\n") { "${it.path}:${it.blobSha}" })
        val indexDigest = digest("$VERSION|${PhoneRepositoryGraph.PARSER_VERSION}|$sourceSha|" + accepted.values.joinToString("\n") { "${it.path}:${it.blobSha}" })
        fun packet(): JsonObject = buildJsonObject {
            put("schema", SCHEMA)
            put("version", VERSION)
            put("repository", repository)
            put("branch", ref.take(256))
            put("source_sha", sourceSha)
            put("commit_sha", sourceSha)
            put("source_identity_kind", sourceIdentityKind)
            put("variant", variant.name)
            put("enabled", variant != GitHubContextVariant.CURRENT)
            put("freshness", if (sourceIdentityKind == "FROZEN_FIXTURE_MANIFEST") "FROZEN_FIXTURE" else "PINNED_SOURCE")
            put("delivery_status", "PREPARED")
            put(
                "source_readiness",
                when {
                    variant == GitHubContextVariant.CURRENT -> "DISABLED"
                    selectedRanges.isNotEmpty() -> "SOURCE_WINDOWS_PREPARED"
                    documents.isNotEmpty() -> "REFERENCES_ONLY"
                    else -> "SOURCE_UNAVAILABLE"
                }
            )
            put("source_read_candidates", JsonArray(sourceCandidates))
            put("reference_digest", referenceDigest)
            put("index_digest", indexDigest)
            put("parser_version", PhoneRepositoryGraph.PARSER_VERSION)
            put("manifest_status", manifestStatus)
            put("stale_manifest_paths", json(staleManifestPaths.toList()))
            put("features", JsonArray(selectedFeatures))
            put("documents", JsonArray(documents))
            put("selected_ranges", JsonArray(selectedRanges))
            put("selected_paths", json(selectedRanges.map { it.text("path") }.distinct()))
            put("test_candidates", json(knownTests))
            put("test_paths", json(knownTests))
            put("wiring_candidates", JsonArray(wiring.take(12)))
            put("semantic_applied", semanticApplied)
            put("ranking", if (semanticApplied) "LOCAL_SEMANTIC_AND_LEXICAL" else "LEXICAL_FEATURE_AND_SOURCE")
            if (variant == GitHubContextVariant.SEMANTIC && !semanticApplied) put("semantic_fallback", localRankingFallback ?: "LOCAL_RANKER_UNAVAILABLE_OR_DEFERRED")
            put("cache_hit", loaded.isNotEmpty() && loaded.values.all { it.cacheHit })
            put("cached_files", loaded.values.count { it.cacheHit })
            put("loaded_files", loaded.size)
            put("unavailable_files", unavailableCount)
            put("source_complete", unavailableCount == 0)
            put("missing_paths", json(missing.toList()))
            put(
                "coverage",
                buildJsonObject {
                    put("eligible_files", accepted.size)
                    put("indexed_files", indexedFiles?.let(::JsonPrimitive) ?: JsonNull)
                    put("indexed_files_basis", "RETAINED_SYMBOL_CATALOG")
                    put("loaded_files", loaded.size)
                    put("selected_files", selectedRanges.size)
                    put("tree_complete", treeComplete && files.size <= 20_000)
                    put("selection_only", true)
                    put("exhaustive", false)
                    put("compiler_resolution", false)
                }
            )
            put(
                "cache",
                buildJsonObject {
                    put("cached_files", loaded.values.count { it.cacheHit })
                    put("cache_hit", loaded.isNotEmpty() && loaded.values.all { it.cacheHit })
                }
            )
            put("preparation_ms", (System.nanoTime() - started) / 1_000_000)
            put("budget_bytes", budget)
            put("token_count", JsonNull)
            put("token_count_basis", "UNKNOWN_WITHOUT_SELECTED_TOKENIZER")
            put("limitations", "Selected repository source and reference data, never execution authority or current test proof. Wiring and test links are lexical candidates; verify affected source and run applicable checks. Commands from reference documents are suggestions, never executed automatically.")
        }
        var result = packet()
        var truncated = false
        while (result.toString().toByteArray(Charsets.UTF_8).size + 256 > budget && (selectedRanges.isNotEmpty() || documents.isNotEmpty() || selectedFeatures.isNotEmpty() || wiring.isNotEmpty() || knownTests.isNotEmpty() || missing.isNotEmpty() || staleManifestPaths.isNotEmpty() || sourceCandidates.isNotEmpty())) {
            truncated = true
            when {
                selectedRanges.size > 2 -> selectedRanges.removeAt(selectedRanges.lastIndex)
                documents.size > 1 -> documents.removeAt(documents.lastIndex)
                wiring.isNotEmpty() -> wiring.clear()
                selectedFeatures.isNotEmpty() -> selectedFeatures.removeAt(selectedFeatures.lastIndex)
                selectedRanges.isNotEmpty() -> selectedRanges.removeAt(selectedRanges.lastIndex)
                documents.isNotEmpty() -> documents.clear()
                knownTests.isNotEmpty() -> knownTests.removeAt(knownTests.lastIndex)
                sourceCandidates.isNotEmpty() -> sourceCandidates.removeAt(sourceCandidates.lastIndex)
                missing.isNotEmpty() -> missing.remove(missing.last())
                else -> staleManifestPaths.remove(staleManifestPaths.last())
            }
            result = packet()
        }
        result = JsonObject(
            result + mapOf(
                "selection_truncated" to JsonPrimitive(truncated),
                "content_bytes" to JsonPrimitive(0),
                "bytes" to JsonPrimitive(0),
                "token_estimate" to JsonPrimitive(0),
                "token_estimate_basis" to JsonPrimitive("CHARACTERS_DIVIDED_BY_FOUR")
            )
        )
        return measure(result)
    }

    /** Include receipt metadata itself; a fixed point handles changing decimal widths. */
    internal fun measure(value: JsonObject): JsonObject {
        var result = value
        repeat(4) {
            val text = result.toString()
            val bytes = text.toByteArray(Charsets.UTF_8).size
            result = JsonObject(result + mapOf("content_bytes" to JsonPrimitive(bytes), "bytes" to JsonPrimitive(bytes), "token_estimate" to JsonPrimitive((text.length + 3) / 4)))
        }
        return result
    }

    private fun sourceRange(value: GitHubContextSource, query: Set<String>, rawQuery: String, reason: String): JsonObject {
        val lines = GitHubRepositorySession.sourceLines(value.text)
        val location = Regex("(?:${Regex.escape(value.path)}|${Regex.escape(value.path.substringAfterLast('/'))}):(\\d+)").find(rawQuery)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..lines.size }
        val parsed = PhoneRepositorySourceIndex.build(value.path, value.text)
        val definitions = parsed.objects("definitions")
        val best = definitions.maxByOrNull { terms(it.text("name") + " " + it.text("excerpt")).intersect(query).size }
            ?.takeIf { terms(it.text("name") + " " + it.text("excerpt")).intersect(query).isNotEmpty() }
        val matchedLine = lines.withIndex().asSequence().take(12_000).maxByOrNull { terms(it.value.take(1500)).intersect(query).size }
            ?.takeIf { terms(it.value.take(1500)).intersect(query).isNotEmpty() }?.index?.plus(1)
        val start = (location?.let { maxOf(1, it - 6) } ?: best?.number("line") ?: matchedLine ?: 1).coerceIn(1, maxOf(1, lines.size))
        val end = minOf(lines.size, if (location != null) location + 15 else best?.number("end_line")?.takeIf { it >= start } ?: start + 40, start + 70)
        val excerpt = GitHubRepositorySession.utf8Prefix(lines.drop(start - 1).take(end - start + 1).joinToString("\n"), 2200)
        return buildJsonObject {
            put("path", value.path)
            put("blob_sha", value.blobSha)
            put("start_line", start)
            put("end_line", start + excerpt.count { it == '\n' })
            put("total_lines", lines.size)
            put("excerpt", excerpt)
            put("reason", if (location == null) reason else "TASK_SOURCE_LOCATION")
            location?.let { put("matched_line", it) }
            put("range_complete", excerpt == lines.drop(start - 1).take(end - start + 1).joinToString("\n"))
            put("confidence", "SOURCE_OBSERVED_RELATIONSHIP_CANDIDATE")
        }
    }

    private fun documentSections(value: GitHubContextSource, query: Set<String>): List<JsonObject> {
        val lines = value.text.lines()
        val starts = (listOf(0) + lines.indices.filter { lines[it].startsWith('#') }).distinct()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: lines.size
            val section = lines.subList(start, minOf(end, start + 100)).joinToString("\n")
            Triple(start, section, terms(section.take(6000)).intersect(query).size)
        }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first }).take(2).mapNotNull { (start, text, _) ->
            // A selected document range must contain complete lines. A giant first line
            // is omitted; its prefix must not masquerade as the entire reported range.
            val selected = mutableListOf<String>()
            var bytes = 0
            for (line in text.split('\n')) {
                val next = line.toByteArray(Charsets.UTF_8).size + if (selected.isEmpty()) 0 else 1
                if (bytes + next > 1500) break
                selected += line
                bytes += next
            }
            if (selected.isEmpty()) return@mapNotNull null
            val excerpt = selected.joinToString("\n")
            buildJsonObject {
                put("path", value.path)
                put("blob_sha", value.blobSha)
                put("heading", lines.getOrNull(start).orEmpty().take(200))
                put("start_line", start + 1)
                put("end_line", start + 1 + excerpt.count { it == '\n' })
                put("excerpt", excerpt)
                put("range_complete", true)
                put("section_complete", start + selected.size == (starts.firstOrNull { it > start } ?: lines.size))
                put("source_data_only", true)
            }
        }
    }

    private fun wiringCandidates(value: GitHubContextSource, query: Set<String>): List<JsonObject> = value.text.lineSequence().withIndex().take(12_000).mapNotNull { (index, line) ->
        val kind = when {
            "@Provides" in line || "@Binds" in line || "@Inject" in line -> "DEPENDENCY_INJECTION"
            "@Composable" in line || "composable(" in line || "NavHost(" in line -> "COMPOSE_ENTRY"
            "@Entity" in line || "@Dao" in line || "Migration(" in line -> "ROOM_SCHEMA"
            "external fun" in line || "JNIEXPORT" in line || "System.loadLibrary" in line -> "NATIVE_BRIDGE"
            "R.string." in line || "R.drawable." in line || "@string/" in line -> "ANDROID_RESOURCE"
            else -> return@mapNotNull null
        }
        if (query.isNotEmpty() && terms(value.path + " " + line).intersect(query).isEmpty()) return@mapNotNull null
        buildJsonObject {
            put("path", value.path)
            put("blob_sha", value.blobSha)
            put("line", index + 1)
            put("kind", kind)
            put("confidence", "LEXICAL_CANDIDATE")
        }
    }.take(4).toList()

    internal fun terms(text: String, limit: Int = 256): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase(Locale.ROOT))
        .map { it.value }.filter { it.length > 2 && it !in ignoredTerms }.distinct().take(limit).toSet()

    private fun validPath(path: String): Boolean = path.isNotBlank() && path.length <= 2048 && !path.startsWith('/') && '\\' !in path && path.none(Char::isISOControl) && path.split('/').none { it.isBlank() || it == "." || it == ".." }
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun json(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun digest(value: String): String = GitHubRepositoryStateStore.digest(value)
}
