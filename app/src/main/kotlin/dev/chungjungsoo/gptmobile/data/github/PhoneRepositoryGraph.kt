package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A revision-pinned, phone-local graph built only from the selected tree and its cached blobs. */
internal object PhoneRepositoryGraph {
    const val PARSER_VERSION = PhoneRepositorySourceIndex.VERSION
    private const val MAX_TREE_ENTRIES = 20_000
    private const val MAX_NODES = 40_000
    private const val MAX_EDGES = 60_000
    private const val MAX_NEIGHBORS = 2000
    private const val DEFAULT_PAGE_SIZE = 60
    private val actions = setOf("status", "index", "architecture", "query", "node", "callers", "callees", "path", "impact", "metrics")
    private val identifier = Regex("[A-Za-z_$][A-Za-z_0-9$]*")
    private val queryAliases = mapOf(
        "microphone" to setOf("capture", "recorder"),
        "pronunciation" to setOf("speech", "tts", "lexicon"),
        "scrolling" to setOf("scroll", "anchor"),
        "map" to setOf("graph")
    )
    private val sourceExtensions = listOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "c", "cc", "cpp", "h", "hpp", "cs", "rs", "go", "swift")
    private const val LIMITATIONS = "Tree containment is observed. Symbols, imports and call relationships are bounded source candidates, without compiler resolution. Missing, generated, dynamic, aliased or unscanned references remain unknown. No executed tests are implied."

    fun sourceIndex(path: String, text: String, checkpoint: () -> Unit = {}): JsonObject = PhoneRepositorySourceIndex.build(path, text, checkpoint)

    fun execute(context: GitHubTaskSnapshot, tree: JsonObject, cached: Map<String, JsonObject>, status: JsonObject, arguments: JsonObject, restored: PhoneMapTopology? = null, capture: ((PhoneMapTopology) -> Unit)? = null): JsonObject {
        val action = arguments.text("action").ifBlank { "status" }
        if (action !in actions) fail("PHONE_GRAPH_OPERATION_UNSUPPORTED", "This analysis is available through the optional Gateway. The phone map supports files, symbols, import/call candidates, paths and impact.", "SELECT_GATEWAY_ANALYSIS")
        require(context.commitSha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))) { "The phone map requires an immutable commit." }
        val observedTree = tree.text("tree_sha").ifBlank { tree.text("sha") }
        require(observedTree.isBlank() || observedTree.equals(context.treeSha, true)) { "The phone map tree differs from the selected immutable revision." }
        val rawEntries = tree.objects("tree")
        val entries = rawEntries.asSequence().filter { validPath(it.text("path")) && it.text("type") in setOf("tree", "blob") }.distinctBy { it.text("path") }.take(MAX_TREE_ENTRIES).sortedBy { it.text("path") }.toList()
        val byPath = entries.associateBy { it.text("path") }
        val treeIncomplete = tree["truncated"] == JsonPrimitive(true) || entries.size != rawEntries.size
        val eligible = entries.count { it.text("type") == "blob" && it.text("mode") !in setOf("120000", "160000") && PhoneRepositorySourceIndex.supports(it.text("path")) }
        val shards = cached.toSortedMap().mapNotNull { (path, blob) ->
            val entry = byPath[path]?.takeIf { it.text("type") == "blob" && it.text("mode") !in setOf("120000", "160000") } ?: return@mapNotNull null
            if (blob.text("sha").isBlank() || !blob.text("sha").equals(entry.text("sha"), true)) return@mapNotNull null
            val retained = (blob["phone_graph"] as? JsonObject)?.takeIf { it.number("parser_version") == PARSER_VERSION && it.text("language") == path.substringAfterLast('.', "").lowercase(Locale.ROOT) && it.text("source_hash").matches(Regex("[a-f0-9]{64}")) }
            val shard = retained ?: (blob["content"] as? JsonPrimitive)?.contentOrNull?.let { sourceIndex(path, it) } ?: return@mapNotNull null
            path to shard
        }.toMap()
        val statusOnly = action == "status" || action == "index"
        val scanned = if (statusOnly) status.number("phone_indexed_supported_files", shards.count { (_, shard) -> shard["supported"] == JsonPrimitive(true) }).coerceIn(0, eligible) else shards.count { (_, shard) -> shard["supported"] == JsonPrimitive(true) }
        val parserPartial = if (statusOnly) status.number("phone_partial_supported_files", shards.count { (_, shard) -> shard["supported"] == JsonPrimitive(true) && shard["complete"] != JsonPrimitive(true) }).coerceIn(0, scanned) else shards.count { (_, shard) -> shard["supported"] == JsonPrimitive(true) && shard["complete"] != JsonPrimitive(true) }
        val collected = status["complete"] == JsonPrimitive(true)
        val processingComplete = status["preparation_finished"] == JsonPrimitive(true) || collected
        val fullCoverage = !treeIncomplete && scanned == eligible && parserPartial == 0 && status["cached_sources_truncated"] != JsonPrimitive(true)
        val retryableFiles = status.number("phone_retryable_files").coerceIn(0, (eligible - scanned + parserPartial).coerceAtLeast(0))
        fun metadata(nodes: Int? = null, edges: Int? = null, graphTruncated: Boolean = false, unresolved: Int = 0) = buildJsonObject {
            put("repository", "${context.owner}/${context.repo}")
            put("branch", context.ref)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("source", "phone")
            put("analysis_kind", "lexical")
            put("compiler_resolution", false)
            put("inventory_available", true)
            put("tree_complete", !treeIncomplete)
            put("expected_files", eligible)
            put("processed_files", scanned)
            put("cached_files", if (statusOnly) status.number("indexed_blobs", shards.size) else shards.size)
            put("inventory_files", entries.count { it.text("type") == "blob" })
            put("coverage_scope", "SUPPORTED_SOURCE_CANDIDATES")
            listOf("qualification_scope", "qualification_paths", "qualification_expected_files", "qualification_complete", "index_order_version").forEach { field ->
                status[field]?.let { put(field, it) }
            }
            put("source_collection_complete", collected)
            put("processing_complete", processingComplete)
            put("coverage_complete", fullCoverage && !graphTruncated)
            put("complete", fullCoverage && !graphTruncated)
            put("graph_truncated", graphTruncated)
            put("retryable_files", retryableFiles)
            put("unresolved_references", unresolved)
            nodes?.let { put("nodes", it) }
            edges?.let { put("edges", it) }
            put(
                "file_statuses",
                buildJsonObject {
                    put("indexed", scanned - parserPartial)
                    put("partial", (parserPartial + eligible - scanned - retryableFiles).coerceAtLeast(0))
                    put("unsupported", entries.count { it.text("type") == "blob" } - eligible)
                    put("failed", retryableFiles)
                }
            )
        }
        fun sourceCoverage(graph: JsonObject) = when {
            graph["complete"] == JsonPrimitive(true) -> "INDEXED"
            scanned == 0 -> "TREE_ONLY"
            else -> "PARTIALLY_INDEXED"
        }
        fun base(graph: JsonObject, outcome: String = "INDEXED") = buildJsonObject {
            put("status", outcome)
            put("source_coverage_state", sourceCoverage(graph))
            put("graph", graph)
            put("map_source", "phone")
            put("graph_source", "PHONE")
            put("compiler_resolution", false)
            put("limitations", LIMITATIONS)
            listOf("qualification_scope", "qualification_paths", "qualification_expected_files", "qualification_complete").forEach { field ->
                status[field]?.let { put(field, it) }
            }
        }
        if (action == "status" || action == "index") {
            val graph = metadata()
            return JsonObject(status + base(graph, sourceCoverage(graph)) + mapOf("has_more" to (status["has_more"] ?: JsonPrimitive(false))))
        }
        val focus = (arguments["paths"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.onEach { require(validPath(it)) { "Use a repository-relative source path." } }.take(8)
        val topology = restored ?: buildTopology(entries, byPath, shards, focus).also { capture?.invoke(it) }
        var graphTruncated = topology.truncated
        val unresolved = topology.unresolved
        val allNodes = topology.nodes
        val nodes = allNodes.associateBy { it.text("id") }
        val allEdges = topology.edges
        val query = arguments.text("query").trim().take(2000)
        val symbol = arguments.text("symbol").take(4000)
        fun matches(value: String): List<JsonObject> = nodes[value]?.let(::listOf) ?: allNodes.filter { it.text("name") == value || it.text("qualified") == value || it.text("path") == value }
        val selected = matches(symbol.ifBlank { arguments.text("source") })
        fun selectedIds(): Set<String> {
            val ids = selected.mapTo(linkedSetOf()) { it.text("id") }
            val files = selected.filter { it.text("kind") == "file" }.mapTo(mutableSetOf()) { it.text("path") }
            val directories = selected.filter { it.text("kind") == "directory" }.map { it.text("path") }
            allNodes.filter { it.text("path") in files || directories.any { path -> it.text("path").startsWith("$path/") } }.forEach { ids += it.text("id") }
            return ids
        }
        var resultNodes = allNodes
        var resultEdges = allEdges
        var outcome = "INDEXED"
        val extra = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        when (action) {
            "architecture" -> if (focus.isNotEmpty()) {
                val focused = allNodes.filter { focus.any { path -> it.text("path") == path || it.text("path").startsWith("$path/") } }.mapTo(linkedSetOf()) { it.text("id") }
                val nearby = allEdges.filter { it.text("source") in focused || it.text("target") in focused }
                nearby.forEach {
                    focused += it.text("source")
                    focused += it.text("target")
                }
                resultNodes = allNodes.filter { it.text("id") in focused }
                resultEdges = nearby
            }
            "query" -> {
                val (matches, strategy) = queryNodes(allNodes, query)
                resultNodes = matches
                extra["search_strategy"] = JsonPrimitive(strategy)
            }
            "node" -> {
                val ids = selected.mapTo(mutableSetOf()) { it.text("id") }
                resultEdges = allEdges.filter { it.text("source") in ids || it.text("target") in ids }
                val neighbors = resultEdges.flatMapTo(linkedSetOf()) { listOf(it.text("source"), it.text("target")) }
                resultNodes = selected + allNodes.filter { it.text("id") in neighbors && it.text("id") !in ids }
                extra["candidates"] = JsonArray(selected)
            }
            "callers", "callees" -> {
                val ids = selectedIds().toMutableSet()
                resultEdges = allEdges.filter { it.text("relation") == "call_candidate" && it.text(if (action == "callers") "target" else "source") in ids }
                resultEdges.forEach {
                    ids += it.text("source")
                    ids += it.text("target")
                }
                resultNodes = allNodes.filter { it.text("id") in ids }
                extra["relationship_scope"] = JsonPrimitive("LEXICAL_CALL_CANDIDATES")
            }
            "impact" -> {
                val traversal = traverse(selectedIds().toList(), allEdges.filter { it.text("relation") != "contains" && it.text("relation") != "declares" }, reverse = true)
                val distances = traversal.distances
                resultNodes = allNodes.filter { it.text("id") in distances }
                resultEdges = allEdges.filter { it.text("source") in distances && it.text("target") in distances }
                val fileDistances = linkedMapOf<String, Int>()
                for (node in resultNodes) {
                    val path = node.text("path")
                    val distance = distances.getValue(node.text("id"))
                    fileDistances[path] = minOf(fileDistances[path] ?: distance, distance)
                }
                extra["paths"] = JsonArray(
                    fileDistances.map { (path, distance) ->
                        buildJsonObject {
                            put("path", path)
                            put("distance", distance)
                        }
                    }
                )
                extra["relationship_scope"] = JsonPrimitive("CANDIDATE_IMPACT_REQUIRES_VERIFICATION")
                extra["impact_search_complete"] = JsonPrimitive(traversal.complete)
                if (!traversal.complete) {
                    graphTruncated = true
                    outcome = "IMPACT_SEARCH_LIMIT_REACHED"
                }
            }
            "path" -> {
                val targets = matches(arguments.text("target")).mapTo(mutableSetOf()) { it.text("id") }
                val search = findPath(selected.map { it.text("id") }, targets, allEdges)
                val sequence = search.path
                if (!search.complete) graphTruncated = true
                resultNodes = sequence.mapNotNull(nodes::get)
                resultEdges = sequence.zipWithNext().mapNotNull { (from, to) -> allEdges.firstOrNull { it.text("source") == from && it.text("target") == to } }
                extra["path"] = JsonArray(sequence.map(::JsonPrimitive))
                extra["path_search_complete"] = JsonPrimitive(search.complete)
                if (sequence.isEmpty()) outcome = if (search.complete) "NO_PATH_IN_INDEX" else "PATH_SEARCH_LIMIT_REACHED"
            }
            "metrics" -> {
                extra["metrics"] = buildJsonObject {
                    put("files", entries.count { it.text("type") == "blob" })
                    put("symbols", allNodes.count { it.text("kind") !in setOf("file", "directory") })
                    put("candidate_links", allEdges.count { it.text("confidence") == "candidate" })
                }
            }
        }
        if (resultNodes.isEmpty() && outcome == "INDEXED") outcome = "NOT_FOUND_IN_INDEX"
        val graph = metadata(nodes.size, allEdges.size, graphTruncated, unresolved)
        val snapshot = digest(
            buildString {
                append(context.accountId)
                append('\u0000')
                append(context.connectionUid)
                append('\u0000')
                append(context.owner.lowercase(Locale.ROOT))
                append('/')
                append(context.repo.lowercase(Locale.ROOT))
                append('\u0000')
                append(context.ref)
                append('\u0000')
                append(context.commitSha)
                append('\u0000')
                append(context.treeSha)
                entries.forEach {
                    append('\u0000')
                    append(it.text("path"))
                    append(':')
                    append(it.text("sha"))
                }
                shards.forEach { (path, shard) ->
                    append('\u0000')
                    append(path)
                    append(':')
                    append(shard.text("source_hash"))
                    append(':')
                    append(shard["complete"])
                }
            }
        ).take(24)
        val queryVersion = if (action == "query") "symbol-search-v2:" else ""
        val queryIdentity = digest(queryVersion + JsonObject(arguments.filterKeys { it !in setOf("result_cursor", "budget_bytes") }.toSortedMap())).take(24)
        return page(base(graph, outcome), resultNodes, resultEdges, arguments, snapshot, queryIdentity, extra)
    }

    private fun buildTopology(entries: List<JsonObject>, byPath: Map<String, JsonObject>, shards: Map<String, JsonObject>, focus: List<String>): PhoneMapTopology {
        val nodes = linkedMapOf<String, JsonObject>()
        val edges = linkedMapOf<String, JsonObject>()
        var graphTruncated = false
        var unresolved = 0
        fun node(value: JsonObject) {
            if (nodes.size < MAX_NODES || value.text("id") in nodes) nodes[value.text("id")] = value else graphTruncated = true
        }
        fun edge(source: String, target: String, relation: String, confidence: String, line: Int? = null, ambiguous: Boolean = false) {
            if (source == target && relation == "contains") return
            if (source !in nodes || target !in nodes) return
            val key = "$source\u0000$target\u0000$relation\u0000${line ?: 0}"
            if (key !in edges && edges.size >= MAX_EDGES) {
                graphTruncated = true
                return
            }
            edges[key] = buildJsonObject {
                put("source", source)
                put("target", target)
                put("relation", relation)
                put("confidence", confidence)
                put("compiler_resolved", false)
                put("ambiguous", ambiguous)
                line?.let { put("line", it) }
            }
        }
        // The tree is available before source downloads. Synthesized parent nodes are deterministic
        // projections of real tree paths and do not invent source relationships.
        val focusedEntries = if (focus.isEmpty()) entries else entries.sortedWith(compareBy<JsonObject> { row -> if (focus.any { row.text("path") == it || row.text("path").startsWith("$it/") }) 0 else 1 }.thenBy { it.text("path") })
        focusedEntries.forEach { entry ->
            val path = entry.text("path")
            if (nodes.size >= MAX_NODES && path !in nodes) {
                graphTruncated = true
                return@forEach
            }
            val segments = path.split('/')
            for (depth in 1 until segments.size) {
                val parent = segments.take(depth).joinToString("/")
                if (parent !in nodes) node(fileNode(parent, "directory"))
            }
            node(fileNode(path, if (entry.text("type") == "tree") "directory" else "file", entry.text("sha"), shards[path]?.get("supported") == JsonPrimitive(true)))
        }
        nodes.values.toList().forEach { current ->
            val path = current.text("path")
            if ('/' in path) edge(path.substringBeforeLast('/'), path, "contains", "tree")
        }
        val orderedShards = shards.entries.sortedWith(compareBy<Map.Entry<String, JsonObject>> { row -> if (focus.any { row.key == it || row.key.startsWith("$it/") }) 0 else 1 }.thenBy { it.key })
        val definitionsByName = mutableMapOf<String, MutableList<String>>()
        val definitionIds = mutableMapOf<String, Map<String, String>>()
        orderedShards.forEach { (path, shard) ->
            val local = shard.objects("definitions").take(2000)
            val ids = local.associate { it.text("key") to symbolId(path, it.text("key")) }
            definitionIds[path] = ids
            local.forEach { definition ->
                val id = ids.getValue(definition.text("key"))
                val name = definition.text("name")
                if (!identifier.matches(name) || definition.number("line") < 1) return@forEach
                node(
                    buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("qualified", "$path · $name")
                        put("path", path)
                        put("kind", definition.text("kind"))
                        put("line", definition.number("line"))
                        put("end_line", definition.number("end_line").coerceAtLeast(definition.number("line")))
                        put("excerpt", definition.text("excerpt").take(240))
                        put("confidence", "lexical")
                        put("blob_sha", byPath.getValue(path).text("sha"))
                    }
                )
                if (id in nodes) definitionsByName.getOrPut(name) { mutableListOf() }.add(id)
            }
            local.forEach { definition ->
                val id = ids[definition.text("key")] ?: return@forEach
                edge(ids[definition.text("parent")] ?: path, id, "declares", "lexical", definition.number("line"))
            }
        }
        val filePaths = byPath.filterValues { it.text("type") == "blob" }.keys
        val filesByStem = filePaths.groupBy { it.substringAfterLast('/').substringBeforeLast('.') }
        val filesByDirectory = filePaths.groupBy { it.substringBeforeLast('/', "").substringAfterLast('/') }
        val importsByFile = mutableMapOf<String, MutableSet<String>>()
        val importCache = mutableMapOf<String, List<String>>()
        orderedShards.forEach { (path, shard) ->
            shard.objects("imports").take(1000).forEach { imported ->
                // Relative Python imports retain their module in text, not the quoted-module field.
                val importKey = path.substringBeforeLast('/', "") + "\u0000" + imported.text("text") + "\u0000" + imported.text("module")
                val candidates = importCache.getOrPut(importKey) { importCandidates(path, imported, filePaths, filesByStem, filesByDirectory) }
                if (candidates.isEmpty()) unresolved++
                candidates.forEach { target ->
                    importsByFile.getOrPut(path) { linkedSetOf() }.add(target)
                    edge(path, target, "import_candidate", "candidate", imported.number("line"), candidates.size > 1 || imported.text("text").contains('*'))
                }
            }
        }
        orderedShards.forEach { (path, shard) ->
            shard.objects("calls").take(6000).forEach { call ->
                val name = call.text("name")
                val all = definitionsByName[name].orEmpty()
                val sameFile = all.filter { nodes[it]?.text("path") == path }
                val imported = all.filter { nodes[it]?.text("path") in importsByFile[path].orEmpty() }
                // A qualified receiver may refer to any instance type; never silently promote a
                // same-spelled local method over another candidate without compiler information.
                val candidates = (if (call["qualified_receiver"] == JsonPrimitive(true)) all else sameFile.ifEmpty { imported.ifEmpty { all } }).take(8)
                if (candidates.isEmpty()) unresolved++
                if (all.size > 8) graphTruncated = true
                val owner = definitionIds[path]?.get(call.text("owner"))?.takeIf { it in nodes } ?: path
                candidates.forEach { target -> edge(owner, target, "call_candidate", "candidate", call.number("line"), all.size > 1 || call["qualified_receiver"] == JsonPrimitive(true)) }
            }
        }
        return PhoneMapTopology(nodes.values.toList(), edges.values.toList(), graphTruncated, unresolved)
    }

    private fun queryNodes(nodes: List<JsonObject>, query: String): Pair<List<JsonObject>, String> {
        val literal = nodes.filter { it.text("qualified").contains(query, true) || it.text("path").contains(query, true) }
        if (literal.isNotEmpty()) {
            return literal.sortedByDescending { it.text("name").equals(query, true) || it.text("path").equals(query, true) } to "LITERAL"
        }
        if (query.length in 5..80 && identifier.matches(query)) {
            val symbols = nodes.filter { it.text("kind") != "file" && it.text("kind") != "directory" && nearIdentifier(query, it.text("name")) }
            if (symbols.isNotEmpty()) return symbols to "APPROXIMATE_SYMBOL"
        }
        val terms = GitHubTaskContextBundle.terms(query, 16) - setOf("where", "which", "find", "show", "locate", "handles")
        val aliases = terms.flatMap { queryAliases[it].orEmpty() }.toSet() - terms
        if (terms.isEmpty()) return emptyList<JsonObject>() to "NO_MATCH"
        val ranked = nodes.mapNotNull { node ->
            val name = node.text("name").lowercase(Locale.ROOT)
            val path = node.text("path").lowercase(Locale.ROOT)
            val score = 4 * terms.count { it in name } + 2 * terms.count { it in path } +
                aliases.count { it in name || it in path }
            if (score > 0) node to score else null
        }.sortedByDescending { it.second }.map { it.first }
        return ranked to if (ranked.isEmpty()) "NO_MATCH" else "QUERY_TERM_EXPANSION"
    }

    /** One insertion, deletion, substitution or adjacent transposition in a symbol. */
    private fun nearIdentifier(query: String, candidate: String): Boolean {
        if (kotlin.math.abs(query.length - candidate.length) > 1) return false
        val shorter = if (query.length <= candidate.length) query else candidate
        val longer = if (query.length <= candidate.length) candidate else query
        val index = shorter.indices.firstOrNull { !shorter[it].equals(longer[it], true) } ?: return true
        if (longer.length > shorter.length) return shorter.regionMatches(index, longer, index + 1, shorter.length - index, true)
        if (shorter.regionMatches(index + 1, longer, index + 1, shorter.length - index - 1, true)) return true
        return index + 1 < shorter.length &&
            shorter[index].equals(longer[index + 1], true) &&
            shorter[index + 1].equals(longer[index], true) &&
            shorter.regionMatches(index + 2, longer, index + 2, shorter.length - index - 2, true)
    }

    private fun page(base: JsonObject, nodes: List<JsonObject>, edges: List<JsonObject>, arguments: JsonObject, snapshot: String, query: String, extra: Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        val cursor = arguments.text("result_cursor")
        val offset = if (cursor.isBlank()) {
            0
        } else {
            val parts = cursor.split(':')
            if (parts.size != 4 || parts[0] != "phone-v1" || parts[1] != snapshot || parts[2] != query) fail("PHONE_GRAPH_CURSOR_CHANGED", "The selected revision, source coverage or query changed. Refresh this map search.", "RESTART_MAP_SEARCH")
            parts[3].toIntOrNull()?.takeIf { it in 0 until nodes.size } ?: fail("PHONE_GRAPH_CURSOR_INVALID", "This map page is unavailable. Refresh this map search.", "RESTART_MAP_SEARCH")
        }
        val size = arguments.number("page_size", DEFAULT_PAGE_SIZE).coerceIn(1, 80)
        val requestedBudget = arguments.number("budget_bytes", 32000)
        if (requestedBudget < 4096) fail("PHONE_GRAPH_RESULT_TOO_LARGE", "The phone map needs a result budget of at least 4096 bytes.", "INCREASE_GRAPH_RESULT_BUDGET")
        val budget = requestedBudget.coerceAtMost(128000)
        val pageEnd = minOf(nodes.size, offset + size)
        val pageIds = nodes.subList(offset, pageEnd).mapTo(mutableSetOf()) { it.text("id") }
        val pageEdges = edges.filter { it.text("source") in pageIds || it.text("target") in pageIds }
        fun compose(end: Int, edgeLimit: Int): JsonObject {
            val selected = nodes.subList(offset, end)
            val ids = selected.mapTo(mutableSetOf()) { it.text("id") }
            val visibleEdges = pageEdges.filter { it.text("source") in ids && it.text("target") in ids }
            val external = pageEdges.count { (it.text("source") in ids) xor (it.text("target") in ids) }
            val safeExtra = extra.filterKeys { it !in setOf("candidates", "paths", "path") }.toMutableMap()
            (extra["candidates"] as? JsonArray)?.let { candidates -> safeExtra["candidates"] = JsonArray(candidates.filter { (it as? JsonObject)?.text("id") in ids }) }
            (extra["paths"] as? JsonArray)?.let { paths ->
                val selectedPaths = selected.mapTo(mutableSetOf()) { it.text("path") }
                safeExtra["paths"] = JsonArray(paths.filter { (it as? JsonObject)?.text("path") in selectedPaths }.take(80))
            }
            (extra["path"] as? JsonArray)?.let { path ->
                // Path nodes already follow traversal order. Page their IDs together so
                // a full path cannot consume the budget before its first page fits.
                safeExtra["path"] = JsonArray(path.subList(offset, end))
                safeExtra["path_total_nodes"] = JsonPrimitive(path.size)
                safeExtra["path_start_offset"] = JsonPrimitive(offset)
                safeExtra["path_end_offset_exclusive"] = JsonPrimitive(end)
                path.firstOrNull()?.let { safeExtra["path_source_id"] = it }
                path.lastOrNull()?.let { safeExtra["path_target_id"] = it }
            }
            return buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                safeExtra.forEach { (key, value) -> put(key, value) }
                put("nodes", JsonArray(selected))
                put("edges", JsonArray(visibleEdges.take(edgeLimit)))
                put("total_matches", nodes.size)
                put("total_links", edges.size)
                put("external_connections", external)
                put("omitted_links", (visibleEdges.size - edgeLimit).coerceAtLeast(0))
                put("result_offset", offset)
                put("has_more", end < nodes.size)
                if (end < nodes.size) put("next_cursor", "phone-v1:$snapshot:$query:$end")
            }
        }
        fun JsonObject.fits() = toString().toByteArray(Charsets.UTF_8).size <= budget
        val completePage = compose(pageEnd, 160)
        if (completePage.fits()) return completePage

        // Keep the longest contiguous node prefix that fits, then spend its remaining bytes on relationships.
        // Only edges touching this page can affect payload size or external connection counts.
        fun largestFit(lowest: Int, highest: Int, render: (Int) -> JsonObject): JsonObject? {
            var low = lowest
            var high = highest
            var best: JsonObject? = null
            while (low <= high) {
                val mid = (low + high) / 2
                val candidate = render(mid)
                if (candidate.fits()) {
                    best = candidate
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return best
        }
        // The final page drops its continuation cursor, so try all its nodes before shortening it.
        val nodePage = compose(pageEnd, 0).takeIf { it.fits() }
            ?: largestFit(offset + 1, pageEnd - 1) { end -> compose(end, 0) }
            ?: fail("PHONE_GRAPH_RESULT_TOO_LARGE", "This source path does not fit the requested result budget. Increase the result budget.", "NARROW_GRAPH_QUERY")
        val end = offset + (nodePage.getValue("nodes") as JsonArray).size
        return largestFit(1, minOf(160, nodePage.number("omitted_links"))) { limit -> compose(end, limit) } ?: nodePage
    }

    private fun fileNode(path: String, kind: String, sha: String = "", indexed: Boolean = false) = buildJsonObject {
        put("id", path)
        put("path", path)
        put("name", path.substringAfterLast('/'))
        put("qualified", path)
        put("kind", kind)
        put("confidence", "tree")
        put("indexed", indexed)
        if (sha.isNotBlank()) put("blob_sha", sha)
    }

    private fun importCandidates(path: String, imported: JsonObject, files: Set<String>, byStem: Map<String, List<String>>, byDirectory: Map<String, List<String>>): List<String> {
        val relativePython = if (path.endsWith(".py", true)) Regex("^\\s*from\\s+(\\.+)([A-Za-z_0-9.]*)\\s+import\\b").find(imported.text("text")) else null
        val module = imported.text("module").ifBlank {
            relativePython?.let { match -> "../".repeat((match.groupValues[1].length - 1).coerceAtLeast(0)) + "./" + match.groupValues[2].replace('.', '/') }.orEmpty()
        }
        if (module.isNotBlank()) {
            if (!module.startsWith("./") && !module.startsWith("../")) return emptyList()
            val segments = path.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).toMutableList()
            for (part in module.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return emptyList() else segments.removeAt(segments.lastIndex)
                    else -> segments += part
                }
            }
            val target = segments.joinToString("/")
            val candidates = linkedSetOf(target)
            sourceExtensions.forEach {
                candidates += "$target.$it"
                candidates += "$target/index.$it"
                candidates += "$target/__init__.$it"
            }
            if (target.substringAfterLast('.') in setOf("js", "jsx", "mjs", "cjs")) listOf("ts", "tsx", "mts", "cts").forEach { candidates += target.substringBeforeLast('.') + ".$it" }
            return candidates.filter { it in files }.take(8)
        }
        val text = imported.text("text")
        val name = Regex("^\\s*(?:import\\s+(?:static\\s+)?|from\\s+|use\\s+)([A-Za-z_][A-Za-z_0-9.:]*(?:\\.\\*)?)").find(text)?.groupValues?.get(1) ?: return emptyList()
        val suffix = name.removeSuffix(".*").replace("::", ".").replace('.', '/')
        val pool = if (name.endsWith(".*")) byDirectory[suffix.substringAfterLast('/')].orEmpty() else byStem[suffix.substringAfterLast('/')].orEmpty() + byDirectory[suffix.substringAfterLast('/')].orEmpty().filter { it.substringAfterLast('/').substringBeforeLast('.') == "__init__" }
        return pool.asSequence().filter { candidate ->
            val stem = candidate.substringBeforeLast('.')
            if (name.endsWith(".*")) candidate.substringBeforeLast('/', "").let { it == suffix || it.endsWith("/$suffix") } else stem == suffix || stem.endsWith("/$suffix") || stem == "$suffix/__init__" || stem.endsWith("/$suffix/__init__")
        }.take(8).toList()
    }

    private data class Traversal(val distances: Map<String, Int>, val complete: Boolean)

    private fun traverse(start: List<String>, edges: List<JsonObject>, reverse: Boolean): Traversal {
        val adjacent = edges.groupBy { it.text(if (reverse) "target" else "source") }
        val distance = linkedMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        var complete = start.size <= MAX_NEIGHBORS
        start.take(MAX_NEIGHBORS).forEach {
            distance[it] = 0
            queue.addLast(it)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val depth = distance.getValue(current)
            adjacent[current].orEmpty().forEach { edge ->
                val next = edge.text(if (reverse) "source" else "target")
                if (next !in distance) {
                    if (depth < 8 && distance.size < MAX_NEIGHBORS) {
                        distance[next] = depth + 1
                        queue.addLast(next)
                    } else {
                        complete = false
                    }
                }
            }
        }
        return Traversal(distance, complete)
    }

    private data class PathSearch(val path: List<String>, val complete: Boolean)

    private fun findPath(start: List<String>, targets: Set<String>, edges: List<JsonObject>): PathSearch {
        val adjacent = edges.groupBy { it.text("source") }
        val previous = mutableMapOf<String, String?>()
        val queue = ArrayDeque<String>()
        var complete = start.size <= MAX_NEIGHBORS
        start.take(MAX_NEIGHBORS).forEach {
            previous[it] = null
            queue.addLast(it)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in targets) {
                val result = mutableListOf(current)
                var prior = previous[current]
                // The predecessor map is already bounded; keep the entire source-to-target path.
                // Response-byte and node-page limits still apply after reconstruction.
                while (prior != null) {
                    result += prior
                    prior = previous[prior]
                }
                return PathSearch(result.reversed(), true)
            }
            // A discovery limit must not hide a target already waiting in the bounded queue.
            adjacent[current].orEmpty().forEach { edge ->
                val next = edge.text("target")
                if (next !in previous) {
                    if (previous.size < MAX_NEIGHBORS) {
                        previous[next] = current
                        queue.addLast(next)
                    } else {
                        complete = false
                    }
                }
            }
        }
        return PathSearch(emptyList(), complete)
    }

    // A leading slash cannot be a valid repository-relative file ID. Hashing also
    // keeps source paths and long identifiers within the tool's symbol limit.
    private fun symbolId(path: String, key: String) = "/symbol/" + digest("$path\u0000$key")
    private fun validPath(path: String) = path.isNotBlank() && path.length <= 2048 && !path.startsWith('/') && '\\' !in path && path.none(Char::isISOControl) && path.count { it == '/' } < 128 && path.split('/').none { it.isBlank() || it == "." || it == ".." }
    private fun digest(value: String) = PhoneRepositorySourceIndex.digest(value)
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String, fallback: Int = 0) = (get(key) as? JsonPrimitive)?.intOrNull ?: fallback
    private fun JsonObject.objects(key: String) = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun fail(code: String, message: String, repair: String): Nothing = throw GatewayGraphResultException(code, message, repair)
}
