package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRepositoryGraphTest {
    private val context = GitHubTaskSnapshot("account", "connection", "conversation", "map", "owner", "repo", "main", "a".repeat(40), "b".repeat(40))

    @Test fun separateChatsReuseTopologyAndMissingPagesTriggerARebuild() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val tree = tree("src/A.kt")
        val args = buildJsonObject { put("action", "architecture") }
        val scope = "account:owner/repo"
        val key = PhoneMapTopology.key(context, emptyMap(), null)
        var captured: PhoneMapTopology? = null
        val initial = PhoneRepositoryGraph.execute(context, tree, emptyMap(), buildJsonObject {}, args) { captured = it }
        requireNotNull(captured).save(store, scope, key)
        val next = context.copy(conversationId = "another", taskId = "new", ref = "topic", commitSha = "c".repeat(40))
        assertEquals(key, PhoneMapTopology.key(next, emptyMap(), null))
        val retained = requireNotNull(PhoneMapTopology.load(store, scope, key))
        val result = PhoneRepositoryGraph.execute(next, tree, emptyMap(), buildJsonObject {}, args, retained) { error("The shared map was rebuilt") }
        assertEquals(initial["nodes"], result["nodes"])
        assertEquals(next.commitSha, result.getValue("graph").jsonObject.text("commit_sha"))
        assertNotEquals(key, PhoneMapTopology.key(next.copy(connectionUid = "other"), emptyMap(), null))
        assertNotEquals(key, PhoneMapTopology.key(next.copy(treeSha = "d".repeat(40)), emptyMap(), null))
        store.remove(scope, "$key:nodes:0")
        assertTrue(PhoneMapTopology.load(store, scope, key) == null)
    }

    @Test fun emptySourceCacheStillDisplaysPinnedDirectoryAndFileStructure() {
        val tree = tree("src/A.kt", "src/B.kt")
        val status = execute(tree, action = "status")
        assertEquals("INDEXED", status.text("status"))
        assertEquals(context.commitSha, status["graph"]!!.jsonObject.text("commit_sha"))
        assertEquals("false", status["graph"]!!.jsonObject.text("coverage_complete"))
        val map = execute(tree)
        assertEquals(setOf("src", "src/A.kt", "src/B.kt"), map.objects("nodes").map { it.text("id") }.toSet())
        assertEquals(2, map.objects("edges").size)
        assertTrue(map.objects("edges").all { it.text("relation") == "contains" && it.text("confidence") == "tree" })
    }

    @Test fun kotlinCallsUseRealCodeAndRetainCandidateQualifications() {
        val sources = mapOf(
            "A.kt" to """
            fun caller() {
                callee()
                // fake()
                val text = "fake()"
            }
            fun callee() { }
            /* fun fake() { caller() } */
            """.trimIndent()
        )
        val map = execute(tree(*sources.keys.toTypedArray()), cached(sources))
        val symbols = map.objects("nodes").filter { it.text("kind") == "function" }
        assertEquals(setOf("caller", "callee"), symbols.map { it.text("name") }.toSet())
        val call = map.objects("edges").single { it.text("relation") == "call_candidate" }
        assertEquals(symbols.single { it.text("name") == "caller" }.text("id"), call.text("source"))
        assertEquals(symbols.single { it.text("name") == "callee" }.text("id"), call.text("target"))
        assertEquals("candidate", call.text("confidence"))
        assertEquals("false", call.text("compiler_resolved"))
        assertEquals("false", map.text("compiler_resolution"))
    }

    @Test fun queryFindsSpokenDescriptionsAndRecoversOneMisspelledSymbol() {
        val sources = mapOf("Audio.kt" to "fun startRecorder() {}\nfun dispatch() {}", "Display.kt" to "fun refresh() {}")
        val tree = tree(*sources.keys.toTypedArray())
        val cache = cached(sources)
        fun search(query: String) = execute(tree, cache, "query", buildJsonObject { put("query", query) })

        val described = search("Where is microphone capture?")
        assertEquals("QUERY_TERM_EXPANSION", described.text("search_strategy"))
        assertEquals("startRecorder", described.objects("nodes").first().text("name"))
        val corrected = search("disptach")
        assertEquals("APPROXIMATE_SYMBOL", corrected.text("search_strategy"))
        assertEquals(listOf("dispatch"), corrected.objects("nodes").map { it.text("name") })
        assertEquals("LITERAL", search("dispatch").text("search_strategy"))
        assertEquals("NOT_FOUND_IN_INDEX", search("unrelatedxyz").text("status"))
    }

    @Test fun pythonScopesEndAtDedentAndDocstringsDoNotBecomeCode() {
        val code = "def first():\n    second()\n    \"\"\"\n    fake()\n    \"\"\"\ndef second():\n    pass\nsecond()\n"
        val shard = PhoneRepositoryGraph.sourceIndex("code.py", code)
        assertEquals(listOf("first", "second"), shard.objects("definitions").map { it.text("name") })
        val calls = shard.objects("calls")
        assertEquals(2, calls.size)
        assertTrue(calls.first().text("owner").endsWith(":first"))
        assertEquals("", calls.last().text("owner"))
    }

    @Test fun scriptImportsUseRelativeTreeCandidatesAndIgnoreCommentedImports() {
        val sources = mapOf("src/A.ts" to "import { work } from './work.js'\n// import { fake } from './fake.js'\nexport function run() { work() }", "src/work.ts" to "export function work() { return 2 }", "src/fake.ts" to "export function fake() {}")
        val result = execute(tree(*sources.keys.toTypedArray()), cached(sources))
        val imports = result.objects("edges").filter { it.text("relation") == "import_candidate" }
        assertEquals(1, imports.size)
        assertEquals("src/A.ts", imports.single().text("source"))
        assertEquals("src/work.ts", imports.single().text("target"))
        assertEquals("candidate", imports.single().text("confidence"))
    }

    @Test fun overloadedQualifiedCallsRemainAmbiguousRatherThanSelectingOneTarget() {
        val sources = mapOf("Main.kt" to "fun run() { value.work() }", "One.kt" to "fun work() {}", "Two.kt" to "fun work() {}")
        val result = execute(tree(*sources.keys.toTypedArray()), cached(sources))
        val calls = result.objects("edges").filter { it.text("relation") == "call_candidate" }
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.text("ambiguous") == "true" && it.text("confidence") == "candidate" })
    }

    @Test fun staleBlobIdentityCannotSupplySymbolsToAnotherRevision() {
        val tree = tree("A.kt")
        val wrong = cached(mapOf("A.kt" to "fun stale() {}"), "c".repeat(40))
        val result = execute(tree, wrong)
        assertTrue(result.objects("nodes").none { it.text("name") == "stale" })
        assertEquals("0", result["graph"]!!.jsonObject.text("processed_files"))
    }

    @Test fun treeAndParserTruncationCannotClaimCompleteCoverage() {
        val sources = mapOf("A.kt" to "fun real() {}\n/* missing terminator")
        val result = execute(JsonObject(tree("A.kt") + ("truncated" to JsonPrimitive(true))), cached(sources), complete = true)
        assertEquals("false", result["graph"]!!.jsonObject.text("coverage_complete"))
        assertEquals("false", result["graph"]!!.jsonObject.text("tree_complete"))
        assertEquals("1", result["graph"]!!.jsonObject["file_statuses"]!!.jsonObject.text("partial"))
    }

    @Test fun compactPersistedShardsProduceTheSameMapAsFreshSource() {
        val sources = mapOf("A.kt" to "fun first() { second() }\nfun second() {}")
        val raw = cached(sources)
        val shards = raw.mapValues { (path, blob) ->
            buildJsonObject {
                put("sha", blob.text("sha"))
                put("phone_graph", PhoneRepositoryGraph.sourceIndex(path, sources.getValue(path)))
            }
        }
        assertEquals(execute(tree("A.kt"), raw), execute(tree("A.kt"), shards))
    }

    @Test fun nodeAndCallerQueriesKeepExactSourceIdentity() {
        val sources = mapOf("A.kt" to "fun first() { second() }\nfun second() {}")
        val tree = tree("A.kt")
        val cache = cached(sources)
        val node = execute(tree, cache, "node", buildJsonObject { put("symbol", "second") }).objects("candidates").single()
        val callers = execute(tree, cache, "callers", buildJsonObject { put("symbol", node.text("id")) })
        assertEquals(setOf("first", "second"), callers.objects("nodes").map { it.text("name") }.toSet())
        assertEquals("LEXICAL_CALL_CANDIDATES", callers.text("relationship_scope"))
        assertEquals("A.kt", node.text("path"))
        assertEquals("2", node.text("line"))
    }

    @Test fun paginationIsStableAndExhaustsAllTreeNodes() {
        val tree = tree(*(1..31).map { "File$it.kt" }.toTypedArray())
        var cursor = ""
        val ids = mutableListOf<String>()
        repeat(7) {
            val args = buildJsonObject {
                put("page_size", 5)
                if (cursor.isNotBlank()) put("result_cursor", cursor)
            }
            val result = execute(tree, args = args)
            ids += result.objects("nodes").map { it.text("id") }
            cursor = result.text("next_cursor")
        }
        assertEquals(31, ids.size)
        assertEquals(31, ids.toSet().size)
        assertEquals("", cursor)
    }

    @Test fun continuationRejectsDifferentQueriesRevisionsAccountsAndSourceCoverage() {
        val tree = tree("A.kt", "B.kt", "C.kt")
        val args = buildJsonObject {
            put("page_size", 1)
            put("query", ".kt")
        }
        val first = execute(tree, action = "query", args = args)
        val cursor = first.text("next_cursor")
        assertTrue(cursor.isNotBlank())
        val continuation = JsonObject(args + ("result_cursor" to JsonPrimitive(cursor)))
        fun rejected(newContext: GitHubTaskSnapshot = context, newCache: Map<String, JsonObject> = emptyMap(), newArgs: JsonObject = continuation) {
            val failure = runCatching { PhoneRepositoryGraph.execute(newContext, tree, newCache, buildJsonObject { put("complete", false) }, JsonObject(newArgs + ("action" to JsonPrimitive("query")))) }.exceptionOrNull()
            assertTrue(failure is GatewayGraphResultException)
            assertEquals("PHONE_GRAPH_CURSOR_CHANGED", (failure as GatewayGraphResultException).code)
        }
        rejected(newContext = context.copy(commitSha = "c".repeat(40)))
        rejected(newContext = context.copy(accountId = "another-account"))
        rejected(newCache = cached(mapOf("A.kt" to "fun added() {}")))
        rejected(newArgs = JsonObject(continuation + ("query" to JsonPrimitive("A"))))
    }

    @Test fun malformedCursorAndTraversalFocusAreRejected() {
        val failure = runCatching { execute(tree("A.kt"), args = buildJsonObject { put("result_cursor", "phone-v1:x:y:-1") }) }.exceptionOrNull()
        assertTrue(failure is GatewayGraphResultException)
        assertTrue(runCatching { execute(tree("A.kt"), args = buildJsonObject { put("paths", JsonArray(listOf(JsonPrimitive("../outside")))) }) }.isFailure)
        val safe = execute(tree("../outside", "A.kt"))
        assertEquals(listOf("A.kt"), safe.objects("nodes").map { it.text("id") })
        assertEquals("false", safe["graph"]!!.jsonObject.text("tree_complete"))
    }

    @Test fun wrongTreeIdentityCannotBeRelabelledAsSelectedCommit() {
        val wrong = JsonObject(tree("A.kt") + ("tree_sha" to JsonPrimitive("c".repeat(40))))
        assertTrue(runCatching { execute(wrong) }.isFailure)
    }

    @Test fun craftedTreeFilenameCannotCollideWithSymbolIdentity() {
        val source = "fun real() {}"
        val key = PhoneRepositoryGraph.sourceIndex("A.kt", source).objects("definitions").single().text("key")
        val collidingPath = "A.kt#symbol:$key"
        val result = execute(tree("A.kt", collidingPath), cached(mapOf("A.kt" to source)))
        val symbol = result.objects("nodes").single { it.text("kind") == "function" }
        assertTrue(symbol.text("id").startsWith("/symbol/"))
        assertEquals(72, symbol.text("id").length)
        assertEquals("file", result.objects("nodes").single { it.text("id") == collidingPath }.text("kind"))
        assertEquals("A.kt", symbol.text("path"))
    }

    @Test fun fileCallersIncludeCallsToItsDeclaredSymbolsAndNodeInspectionKeepsNeighbors() {
        val sources = mapOf("A.kt" to "fun caller() { target() }", "B.kt" to "fun target() {}")
        val tree = tree("A.kt", "B.kt")
        val cache = cached(sources)
        val callers = execute(tree, cache, "callers", buildJsonObject { put("symbol", "B.kt") })
        assertEquals(1, callers.objects("edges").size)
        assertTrue(callers.objects("nodes").any { it.text("name") == "caller" })
        val detail = execute(tree, cache, "node", buildJsonObject { put("symbol", "target") })
        assertEquals(1, detail.objects("candidates").size)
        assertTrue(detail.objects("edges").any { it.text("relation") == "call_candidate" })
    }

    @Test fun nestedCommentsAndJavaMethodsRemainConservativelySeparated() {
        val kotlin = PhoneRepositoryGraph.sourceIndex("A.kt", "/* outside /* inside */ fun fake() {} */\nfun real() {}")
        assertEquals(listOf("real"), kotlin.objects("definitions").map { it.text("name") })
        val java = PhoneRepositoryGraph.sourceIndex("A.java", "class A {\n  public void run() { helper(); }\n  private void helper() {}\n}")
        assertEquals(setOf("A", "run", "helper"), java.objects("definitions").map { it.text("name") }.toSet())
        assertTrue(java.objects("calls").single().text("owner").endsWith(":run"))
    }

    @Test fun relativePythonImportsAndVariableDefinitionsAreVisibleAsCandidates() {
        val sources = mapOf("pkg/main.py" to "from .support import run\ndef first():\n    run()", "pkg/support.py" to "def run():\n    pass", "State.kt" to "val current = 1\nvar running = false")
        val result = execute(tree(*sources.keys.toTypedArray()), cached(sources))
        assertTrue(result.objects("edges").any { it.text("source") == "pkg/main.py" && it.text("target") == "pkg/support.py" && it.text("relation") == "import_candidate" })
        assertEquals(setOf("current", "running"), result.objects("nodes").filter { it.text("kind") == "variable" }.map { it.text("name") }.toSet())
    }

    @Test fun relativePythonImportCacheRetainsEachCallerDirectory() {
        val sources = mapOf(
            "one/main.py" to "from .support import run\ndef first():\n    run()",
            "one/support.py" to "def run():\n    pass",
            "two/main.py" to "from .support import run\ndef second():\n    run()",
            "two/support.py" to "def run():\n    pass"
        )
        val result = execute(tree(*sources.keys.toTypedArray()), cached(sources))
        val imports = result.objects("edges").filter { it.text("relation") == "import_candidate" }
        assertEquals(
            setOf("one/main.py" to "one/support.py", "two/main.py" to "two/support.py"),
            imports.map { it.text("source") to it.text("target") }.toSet()
        )
    }

    @Test fun symlinksAreInventoryOnlyAndCannotCreateUnfinishableSourceCoverage() {
        val tree = buildJsonObject {
            put(
                "tree",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "link.kt")
                            put("type", "blob")
                            put("mode", "120000")
                            put("sha", "d".repeat(40))
                        }
                    )
                )
            )
            put("truncated", false)
        }
        val result = execute(tree, cached(mapOf("link.kt" to "fun fake() {}")), complete = true)
        assertEquals("0", result["graph"]!!.jsonObject.text("expected_files"))
        assertEquals("true", result["graph"]!!.jsonObject.text("coverage_complete"))
        assertTrue(result.objects("nodes").none { it.text("name") == "fake" })
    }

    @Test fun excessiveDelimiterDepthAndTinyResultBudgetHaveExplicitLimits() {
        val shard = PhoneRepositoryGraph.sourceIndex("Big.kt", "(".repeat(10_001) + ")".repeat(10_001))
        assertEquals("false", shard.text("complete"))
        val failure = runCatching { execute(tree("A.kt"), args = buildJsonObject { put("budget_bytes", 100) }) }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_RESULT_TOO_LARGE", (failure as GatewayGraphResultException).code)
    }

    @Test fun sourceBudgetTrimsPagesWithoutSkippingUnreturnedNodes() {
        val tree = tree(*(1..50).map { "long/" + "x".repeat(120) + "$it.kt" }.toTypedArray())
        val result = execute(
            tree,
            args = buildJsonObject {
                put("budget_bytes", 4096)
                put("page_size", 80)
            }
        )
        assertTrue(result.toString().toByteArray().size <= 4096)
        assertEquals("true", result.text("has_more"))
        val next = execute(
            tree,
            args = buildJsonObject {
                put("budget_bytes", 4096)
                put("page_size", 80)
                put("result_cursor", result.text("next_cursor"))
            }
        )
        assertNotEquals(result.objects("nodes").first().text("id"), next.objects("nodes").first().text("id"))
        assertTrue(result.objects("nodes").map { it.text("id") }.toSet().intersect(next.objects("nodes").map { it.text("id") }.toSet()).isEmpty())
    }

    @Test fun pathAndImpactExposeCandidateScopeAndNeverExecutedTestClaims() {
        val sources = mapOf("A.kt" to "fun first() { second() }\nfun second() {}")
        val tree = tree("A.kt")
        val cache = cached(sources)
        val path = execute(
            tree,
            cache,
            "path",
            buildJsonObject {
                put("source", "first")
                put("target", "second")
            }
        )
        assertEquals(2, path["path"]!!.jsonArray.size)
        assertEquals("call_candidate", path.objects("edges").single().text("relation"))
        val impact = execute(tree, cache, "impact", buildJsonObject { put("symbol", "second") })
        assertEquals(setOf("first", "second"), impact.objects("nodes").map { it.text("name") }.toSet())
        assertEquals("CANDIDATE_IMPACT_REQUIRES_VERIFICATION", impact.text("relationship_scope"))
        assertTrue(impact.text("limitations").contains("No executed tests"))
    }

    @Test fun longPathPreservesItsRequestedStartAndEndAcrossResultPages() {
        val source = callChain(250)
        val tree = tree("Long.kt")
        val cache = cached(mapOf("Long.kt" to source))
        fun symbol(name: String) = execute(tree, cache, "node", buildJsonObject { put("symbol", name) }).objects("candidates").single().text("id")
        val sourceId = symbol("f0")
        val targetId = symbol("f250")
        val path = mutableListOf<String>()
        val names = mutableListOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor = ""
        var pages = 0
        do {
            val result = execute(
                tree,
                cache,
                "path",
                buildJsonObject {
                    put("source", "f0")
                    put("target", "f250")
                    put("budget_bytes", 16000)
                    if (cursor.isNotBlank()) put("result_cursor", cursor)
                },
                complete = true
            )
            val page = result["path"]!!.jsonArray.map { it.jsonPrimitive.content }
            val pageNodes = result.objects("nodes")
            assertTrue(page.isNotEmpty())
            assertEquals(pageNodes.map { it.text("id") }, page)
            assertEquals("251", result.text("path_total_nodes"))
            assertEquals(path.size.toString(), result.text("path_start_offset"))
            assertEquals(path.size.toString(), result.text("result_offset"))
            assertEquals((path.size + page.size).toString(), result.text("path_end_offset_exclusive"))
            assertEquals(sourceId, result.text("path_source_id"))
            assertEquals(targetId, result.text("path_target_id"))
            assertEquals("true", result.text("path_search_complete"))
            assertEquals("false", result["graph"]!!.jsonObject.text("graph_truncated"))
            assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= 16000)
            path += page
            names += pageNodes.map { it.text("name") }
            cursor = result.text("next_cursor")
            assertEquals(cursor.isNotBlank(), result.text("has_more") == "true")
            if (cursor.isNotBlank()) assertTrue("A continuation cursor must advance", cursors.add(cursor))
            assertTrue("Path pagination must terminate", ++pages <= 251)
        } while (cursor.isNotBlank())
        assertTrue(pages > 1)
        assertEquals(251, path.size)
        assertEquals(251, path.toSet().size)
        assertEquals((0..250).map { "f$it" }, names)
        assertEquals(sourceId, path.first())
        assertEquals(targetId, path.last())
    }

    @Test fun pathSearchExaminesTargetsAlreadyQueuedAtTheVisitLimit() {
        val sources = fanOutSources(1999)
        val result = execute(
            tree(*sources.keys.toTypedArray()),
            cached(sources),
            "path",
            buildJsonObject {
                put("source", "root")
                put("target", "leaf1999")
            },
            complete = true
        )
        assertEquals("INDEXED", result.text("status"))
        assertEquals(listOf("root", "leaf1999"), result.objects("nodes").map { it.text("name") })
        assertEquals(2, result["path"]!!.jsonArray.size)
        assertEquals("true", result.text("path_search_complete"))
        assertEquals("false", result["graph"]!!.jsonObject.text("graph_truncated"))
    }

    @Test fun exhaustedPathSearchDoesNotClaimNoPathInACompleteSourceIndex() {
        val sources = fanOutSources(2000)
        val tree = tree(*sources.keys.toTypedArray())
        val cache = cached(sources)
        val coverage = execute(tree, cache, "metrics", complete = true)
        assertEquals("true", coverage["graph"]!!.jsonObject.text("coverage_complete"))
        val result = execute(
            tree,
            cache,
            "path",
            buildJsonObject {
                put("source", "root")
                put("target", "leaf2000")
            },
            complete = true
        )
        assertEquals("PATH_SEARCH_LIMIT_REACHED", result.text("status"))
        assertTrue(result["path"]!!.jsonArray.isEmpty())
        assertEquals("false", result.text("path_search_complete"))
        assertEquals("true", result["graph"]!!.jsonObject.text("graph_truncated"))
        assertEquals("false", result["graph"]!!.jsonObject.text("coverage_complete"))
    }

    @Test fun impactDepthLimitReportsUnseenCallersAsIncomplete() {
        val result = execute(
            tree("Chain.kt"),
            cached(mapOf("Chain.kt" to callChain(10))),
            "impact",
            buildJsonObject { put("symbol", "f10") },
            complete = true
        )
        assertEquals((2..10).map { "f$it" }.toSet(), result.objects("nodes").map { it.text("name") }.toSet())
        assertEquals("IMPACT_SEARCH_LIMIT_REACHED", result.text("status"))
        assertEquals("false", result.text("impact_search_complete"))
        assertEquals("true", result["graph"]!!.jsonObject.text("graph_truncated"))
        assertEquals("false", result["graph"]!!.jsonObject.text("coverage_complete"))
    }

    @Test fun impactLeafAtTheDepthLimitRemainsComplete() {
        val result = execute(
            tree("Chain.kt"),
            cached(mapOf("Chain.kt" to callChain(8))),
            "impact",
            buildJsonObject { put("symbol", "f8") },
            complete = true
        )
        assertEquals((0..8).map { "f$it" }.toSet(), result.objects("nodes").map { it.text("name") }.toSet())
        assertEquals("INDEXED", result.text("status"))
        assertEquals("true", result.text("impact_search_complete"))
        assertEquals("false", result["graph"]!!.jsonObject.text("graph_truncated"))
        assertEquals("true", result["graph"]!!.jsonObject.text("coverage_complete"))
    }

    @Test fun statusPreservesPreparationContinuationAndUsesCompactShardCounts() {
        val status = buildJsonObject {
            put("complete", true)
            put("preparation_finished", true)
            put("phone_indexed_supported_files", 2)
            put("phone_partial_supported_files", 0)
            put("has_more", true)
            put("index_cursor", "revision-bound-index")
        }
        val result = PhoneRepositoryGraph.execute(context, tree("A.kt", "B.kt"), emptyMap(), status, buildJsonObject { put("action", "index") })
        assertEquals("revision-bound-index", result.text("index_cursor"))
        assertEquals("true", result.text("has_more"))
        assertEquals("true", result["graph"]!!.jsonObject.text("coverage_complete"))
        assertEquals("2", result["graph"]!!.jsonObject.text("processed_files"))
        assertEquals("false", result["graph"]!!.jsonObject.text("compiler_resolution"))
    }

    @Test fun completedTraversalKeepsRecoverableFilesVisibleWithoutDoubleCountingCoverage() {
        val status = buildJsonObject {
            put("complete", false)
            put("preparation_finished", true)
            put("phone_indexed_supported_files", 1)
            put("phone_partial_supported_files", 0)
            put("phone_retryable_files", 1)
            put("has_more", false)
        }
        val result = PhoneRepositoryGraph.execute(context, tree("A.kt", "B.kt"), emptyMap(), status, buildJsonObject { put("action", "status") })
        val graph = result["graph"]!!.jsonObject
        assertEquals("1", graph.text("retryable_files"))
        assertEquals("false", graph.text("coverage_complete"))
        assertEquals("true", graph.text("processing_complete"))
        val counts = graph["file_statuses"]!!.jsonObject
        assertEquals("1", counts.text("indexed"))
        assertEquals("0", counts.text("partial"))
        assertEquals("1", counts.text("failed"))
    }

    @Test fun unsupportedOperationsRemainExplicitAndUnsupportedSourcesHaveNoInventedSymbols() {
        val failure = runCatching { execute(tree("A.kt"), action = "diagnose") }.exceptionOrNull()
        assertEquals("PHONE_GRAPH_OPERATION_UNSUPPORTED", (failure as GatewayGraphResultException).code)
        val source = PhoneRepositoryGraph.sourceIndex("README.md", "fun fake() { real() }")
        assertEquals("false", source.text("supported"))
        assertTrue(source.objects("definitions").isEmpty())
        assertTrue(source.objects("calls").isEmpty())
    }

    @Test fun identicalRelativePythonImportsStayWithinTheirSourcePackage() {
        val sources = mapOf(
            "pkg1/main.py" to "from .support import run\ndef first():\n    run()",
            "pkg1/support.py" to "def run():\n    pass",
            "pkg2/main.py" to "from .support import run\ndef second():\n    run()",
            "pkg2/support.py" to "def run():\n    pass"
        )
        val result = execute(tree(*sources.keys.toTypedArray()), cached(sources), complete = true)
        val expected = setOf("pkg1/main.py" to "pkg1/support.py", "pkg2/main.py" to "pkg2/support.py")
        val imports = result.objects("edges").filter { it.text("relation") == "import_candidate" }
        assertEquals(expected, imports.map { it.text("source") to it.text("target") }.toSet())
        val nodes = result.objects("nodes").associateBy { it.text("id") }
        val calls = result.objects("edges").filter { it.text("relation") == "call_candidate" }
        assertEquals(expected, calls.map { nodes.getValue(it.text("source")).text("path") to nodes.getValue(it.text("target")).text("path") }.toSet())
    }

    @Test fun completePathSearchCanStillEstablishAbsenceWithinTheIndex() {
        val sources = mapOf("A.kt" to "", "B.kt" to "")
        val result = execute(
            tree(*sources.keys.toTypedArray()),
            cached(sources),
            "path",
            buildJsonObject {
                put("source", "A.kt")
                put("target", "B.kt")
            },
            complete = true
        )
        assertEquals("NO_PATH_IN_INDEX", result.text("status"))
        assertEquals("true", result.text("path_search_complete"))
        assertEquals("false", result["graph"]!!.jsonObject.text("graph_truncated"))
        assertEquals("true", result["graph"]!!.jsonObject.text("coverage_complete"))
    }

    private fun callChain(last: Int) = (0..last).joinToString("\n") { index -> if (index < last) "fun f$index() { f${index + 1}() }" else "fun f$index() {}" }

    private fun fanOutSources(leaves: Int) = mapOf(
        "Root.kt" to "fun root() {\n" + (1..leaves).joinToString("\n") { "leaf$it()" } + "\n}",
        "Leaves.kt" to (1..leaves).joinToString("\n") { "fun leaf$it() {}" }
    )

    private fun execute(tree: JsonObject, cached: Map<String, JsonObject> = emptyMap(), action: String = "architecture", args: JsonObject = JsonObject(emptyMap()), complete: Boolean = false) = PhoneRepositoryGraph.execute(context, tree, cached, buildJsonObject { put("complete", complete) }, JsonObject(args + ("action" to JsonPrimitive(action))))
    private fun tree(vararg paths: String) = buildJsonObject {
        put(
            "tree",
            JsonArray(
                paths.map { path ->
                    buildJsonObject {
                        put("path", path)
                        put("type", "blob")
                        put("mode", "100644")
                        put("sha", "d".repeat(40))
                    }
                }
            )
        )
        put("truncated", false)
    }
    private fun cached(sources: Map<String, String>, sha: String = "d".repeat(40)) = sources.mapValues { (_, source) ->
        buildJsonObject {
            put("sha", sha)
            put("content", source)
        }
    }
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.objects(key: String) = (get(key) as? JsonArray).orEmpty().map { it.jsonObject }
}
