package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskContextBundleTest {
    private val commit = "a".repeat(40)
    private val implementation = "app/src/main/RepairStore.kt"
    private val test = "app/src/test/RepairStoreTest.kt"
    private val guide = "docs/architecture/repository-guide.md"
    private val code = "package sample\n\nclass RepairStore {\n    fun repairReceipt() = true\n}\n"

    @Test fun smallProfileReceivesOnlyRankedUnreadPathsWithinItsUtf8Budget() {
        val tree = buildJsonObject {
            put(
                "tree",
                JsonArray(
                    (listOf("README.md", "src/Other.kt", "src/RepairStore.kt", "../RepairStore", "docs/RepairStore.md") + (1..8).map { "src/RepairStore$it.kt" }).map { path ->
                        buildJsonObject {
                            put("type", "blob")
                            put("mode", if (path.startsWith("docs/")) "120000" else "100644")
                            put("path", path)
                        }
                    }
                )
            )
        }
        val hints = GitHubTaskContextBundle.cachedPathHints("owner/repo", "main", commit, tree, "Inspect src/RepairStore.kt")
        val paths = hints.getValue("source_read_candidates") as JsonArray
        assertEquals(JsonPrimitive("src/RepairStore.kt"), paths.first())
        assertTrue(paths.size <= 4)
        assertFalse(paths.any { ".." in it.toString() || "docs/" in it.toString() })
        assertTrue(hints.toString().toByteArray().size <= 640)
        assertTrue((GitHubTaskContextBundle.cachedPathHints("owner/repo", "main", commit, null, "Repair")["source_read_candidates"] as JsonArray).isEmpty())
    }

    @Test fun compactSelectionReturnsFirstUsableRetainedWindowAndPreservesOtherPointers() = runBlocking {
        val paths = listOf("src/ParserA.kt", "src/ParserB.kt", "src/ParserC.kt", "src/ParserD.kt")
        val shas = listOf("a", "b", "c", "d").map { it.repeat(40) }
        val tree = buildJsonObject {
            put(
                "tree",
                JsonArray(
                    paths.mapIndexed { index, path ->
                        buildJsonObject {
                            put("type", "blob")
                            put("path", path)
                            put("sha", shas[index])
                            put("size", 32)
                        }
                    }
                )
            )
        }
        val reads = mutableListOf<String>()
        val snapshot = GitHubTaskSnapshot("account", "connection", "chat", "task", "owner", "repo", "main", commit, "e".repeat(40))
        val result = GitHubCompactTaskContext.prepare(snapshot, tree, "parser behavior") { sha ->
            reads += sha
            when (sha) {
                shas[0] -> null
                shas[1] -> buildJsonObject {
                    put("sha", shas[0])
                    put("content", "WRONG_BLOB")
                }
                else -> buildJsonObject {
                    put("sha", sha)
                    put("content", "fun parse() = true")
                }
            }
        }
        assertEquals(shas.take(3), reads)
        val window = result.getValue("source_window").jsonObject
        assertEquals(JsonPrimitive(paths[2]), window["path"])
        assertEquals(JsonPrimitive(shas[2]), window["blob_sha"])
        assertEquals(JsonPrimitive("fun parse() = true"), window["excerpt"])
        assertEquals(JsonArray((paths - paths[2]).map(::JsonPrimitive)), result["source_read_candidates"])
        assertFalse(result.toString().contains("WRONG_BLOB"))
    }

    @Test fun currentArmDoesNotReadSourceOrReferences() = runBlocking {
        val result = build(GitHubContextVariant.CURRENT) { error("CURRENT must not fetch context") }
        assertEquals(JsonArray(emptyList()), result["selected_ranges"])
        assertEquals(JsonArray(emptyList()), result["documents"])
        assertEquals(JsonPrimitive(false), result["enabled"])
    }

    @Test fun taskSelectsCurrentSourceExactSymbolAndRelatedTests() = runBlocking {
        val result = build(GitHubContextVariant.TASK)
        val ranges = (result["selected_ranges"] as JsonArray).map { it.jsonObject }
        val selected = ranges.single { it["path"]?.jsonPrimitive?.content == implementation }
        assertEquals(JsonPrimitive(commit), result["source_sha"])
        assertEquals(JsonPrimitive(4), selected["start_line"])
        assertTrue(selected["excerpt"]!!.jsonPrimitive.content.contains("fun repairReceipt"))
        assertTrue((result["test_candidates"] as JsonArray).contains(JsonPrimitive(test)))
        assertTrue((result["documents"] as JsonArray).any { it.jsonObject["heading"]?.jsonPrimitive?.content == "## Repair receipts" })
        assertEquals(JsonNull, result["token_count"])
        assertEquals(JsonPrimitive(false), result["coverage"]!!.jsonObject["exhaustive"])
        assertEquals(JsonPrimitive("PREPARED"), result["delivery_status"])
        val feature = (result["features"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("REFERENCED_BLOBS_VERIFIED"), feature["freshness"])
        assertEquals(JsonPrimitive(implementation), feature.getValue("routing").jsonObject["entry"])
        assertEquals(JsonPrimitive(false), feature.getValue("routing").jsonObject["execution_authority"])
        assertEquals(JsonArray(listOf(JsonPrimitive("A repair receipt belongs to its selected source."))), feature["invariants"])
    }

    @Test fun referenceArmLoadsDocumentsButNoImplementation() = runBlocking {
        val paths = mutableListOf<String>()
        val result = build(GitHubContextVariant.REFERENCE) { path ->
            paths += path
            source(path)
        }
        assertEquals(JsonArray(emptyList()), result["selected_ranges"])
        assertTrue(paths.contains(guide))
        assertFalse(paths.contains(implementation))
        assertTrue(result.toString().contains("commands_executed\":false"))
    }

    @Test fun referenceSelectsAnUnregisteredRunbookByItsPinnedPath() = runBlocking {
        val path = "docs/runbooks/packet-replay.md"
        val blob = "b".repeat(40)
        val result = GitHubTaskContextBundle.build(
            "owner/repository",
            commit,
            "main",
            listOf(GitHubContextFile(path, blob)),
            "packet replay runbook",
            GitHubContextVariant.REFERENCE,
            load = { GitHubContextSource(it, blob, "# Packet replay\nRead the job receipt.") }
        )
        val document = (result["documents"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive(path), document["path"])
        assertEquals(JsonPrimitive(blob), document["blob_sha"])
        assertEquals(JsonPrimitive(true), document["source_data_only"])
    }

    @Test fun wrongBlobIdentityFailsClosed() = runBlocking {
        var rejected = false
        try {
            build(GitHubContextVariant.TASK) { path -> source(path)?.copy(blobSha = "f".repeat(40)) }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun stackTraceLocationOverridesAHighScoringEarlierDeclaration() = runBlocking {
        val result = build(GitHubContextVariant.TASK, query = "RepairStore.kt:80 repairReceipt") { path ->
            source(path)?.let { if (path == implementation) it.copy(text = code + (1..100).joinToString("\n") { "// source line $it" }) else it }
        }
        val selected = (result["selected_ranges"] as JsonArray).map { it.jsonObject }.single { it["path"] == JsonPrimitive(implementation) }
        assertEquals(JsonPrimitive(80), selected["matched_line"])
        assertEquals(JsonPrimitive("TASK_SOURCE_LOCATION"), selected["reason"])
        assertTrue(selected["start_line"]!!.jsonPrimitive.int <= 80 && selected["end_line"]!!.jsonPrimitive.int >= 80)
    }

    @Test fun staleManifestCannotSelectAnUnrelatedStalePath() = runBlocking {
        val manifest = manifest("f".repeat(40))
        val result = build(GitHubContextVariant.TASK, query = "repair receipts") { path ->
            source(path)?.let { if (path == GitHubTaskContextBundle.FEATURE_MAP) it.copy(text = manifest) else it }
        }
        assertTrue((result["stale_manifest_paths"] as JsonArray).contains(JsonPrimitive(implementation)))
        val ranges = (result["selected_ranges"] as JsonArray).map { it.jsonObject }
        assertTrue(ranges.none { it["path"] == JsonPrimitive(implementation) && it["reason"] == JsonPrimitive("FEATURE_REFERENCE") })
        val feature = (result["features"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("STALE_OR_MISSING_REFERENCES"), feature["freshness"])
        assertFalse(feature.containsKey("routing"))
        assertEquals(JsonArray(emptyList()), feature["invariants"])
        assertEquals(JsonArray(emptyList()), feature["commands"])
    }

    @Test fun deletedManifestReferenceCannotRetainItsFeatureInvariants() = runBlocking {
        val result = GitHubTaskContextBundle.build(
            "owner/repository",
            commit,
            "work",
            files().filterNot { it.path == test },
            "repair receipts",
            budgetBytes = 24_000,
            load = ::source
        )
        assertTrue((result["stale_manifest_paths"] as JsonArray).contains(JsonPrimitive(test)))
        val feature = (result["features"] as JsonArray).single().jsonObject
        assertEquals(JsonPrimitive("STALE_OR_MISSING_REFERENCES"), feature["freshness"])
        assertFalse(feature.containsKey("routing"))
        assertEquals(JsonArray(emptyList()), feature["invariants"])
    }

    @Test fun oversizedDocumentLineIsOmittedWithoutClaimingItWasReadInFull() = runBlocking {
        val document = "# Repair receipt\nExact small line.\n" + "界".repeat(1200) + "\nFollowing line."
        val result = build(GitHubContextVariant.REFERENCE) { path -> source(path)?.let { if (path == guide) it.copy(text = document) else it } }
        val selected = (result["documents"] as JsonArray).single().jsonObject
        val excerpt = selected["excerpt"]!!.jsonPrimitive.content
        assertEquals("# Repair receipt\nExact small line.", excerpt)
        assertEquals(JsonPrimitive(1), selected["start_line"])
        assertEquals(JsonPrimitive(2), selected["end_line"])
        assertEquals(JsonPrimitive(true), selected["range_complete"])
        assertEquals(JsonPrimitive(false), selected["section_complete"])
        assertEquals(document.lines().take(2).joinToString("\n"), excerpt)
    }

    @Test fun semanticArmUsesOnlyProvidedRankerAndReportsFallback() = runBlocking {
        val fallback = build(GitHubContextVariant.SEMANTIC)
        assertEquals(JsonPrimitive(false), fallback["semantic_applied"])
        assertTrue(fallback.containsKey("semantic_fallback"))
        var calls = 0
        val result = GitHubTaskContextBundle.build(
            "owner/repository", commit, "work", files(), "repair receipt", GitHubContextVariant.SEMANTIC, 24_000,
            load = ::source,
            localRanker = { query, candidates ->
                calls++
                assertTrue(query.contains("repair"))
                assertTrue(candidates.keys.contains(implementation))
                mapOf(test to 100.0)
            }
        )
        assertEquals(1, calls)
        assertEquals(JsonPrimitive(true), result["semantic_applied"])
        assertEquals(JsonPrimitive(test), (result["selected_ranges"] as JsonArray).first().jsonObject["path"])
    }

    @Test fun semanticRecordsUseDeepSelectedRangesAndStableImmutableBlobIdentity() = runBlocking {
        val deepSource = (1..250).joinToString("\n") { "// unrelated preface $it" } + "\nfun repairReceipt() = true\n"
        suspend fun records(head: String, blob: String): Map<String, String> {
            var observed = emptyMap<String, String>()
            GitHubTaskContextBundle.build(
                "owner/repository", head, "work", files().map { if (it.path == implementation) it.copy(blobSha = blob) else it }, "repairReceipt", GitHubContextVariant.SEMANTIC, 24_000,
                load = { path -> source(path)?.let { if (path == implementation) it.copy(blobSha = blob, text = deepSource) else it } },
                localRanker = { _, candidates ->
                    observed = candidates
                    candidates.mapValues { 1.0 }
                }
            )
            return observed
        }
        val original = records(commit, "b".repeat(40))
        val nextHead = records("f".repeat(40), "b".repeat(40))
        val changedBlob = records("f".repeat(40), "9".repeat(40))
        assertEquals(original[implementation], nextHead[implementation])
        assertNotEquals(original[implementation], changedBlob[implementation])
        assertTrue(original.getValue(implementation).contains("fun repairReceipt"))
        assertTrue(original.getValue(implementation).contains("lines=251-"))
        assertFalse(original.getValue(implementation).contains("unrelated preface"))
    }

    @Test fun multibyteContentHonorsBudgetWithoutConcealingUnavailableSources() = runBlocking {
        val result = GitHubTaskContextBundle.build(
            "owner/repository",
            commit,
            "work",
            files(),
            "repair receipt",
            GitHubContextVariant.TASK,
            4096,
            load = { path -> source(path)?.let { if (path == implementation) null else it.copy(text = if (path == guide) "# Repair receipt\n" + "界".repeat(4000) else it.text) } }
        )
        assertTrue(result.toString().toByteArray().size <= 4096)
        assertEquals(result.toString().toByteArray().size, result["bytes"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), result["source_complete"])
        assertTrue(result["unavailable_files"]!!.jsonPrimitive.int > 0)
    }

    @Test fun fixtureIdentityAndUnsafePathsRemainExplicit() = runBlocking {
        val result = GitHubTaskContextBundle.build(
            "public/fixture",
            "b".repeat(64),
            "fixture",
            files() + GitHubContextFile("../secret.md", "e".repeat(40)),
            "repair receipt",
            sourceIdentityKind = "FROZEN_FIXTURE_MANIFEST",
            load = { path ->
                assertFalse(path.startsWith(".."))
                source(path)
            }
        )
        assertEquals(JsonPrimitive("FROZEN_FIXTURE_MANIFEST"), result["source_identity_kind"])
        assertEquals(files().size, result["coverage"]!!.jsonObject["eligible_files"]!!.jsonPrimitive.int)
    }

    @Test fun sourceIdentityKindRejectsADigestOfTheWrongShapeBeforeRetrieval() = runBlocking {
        for ((kind, sha) in listOf("GIT_COMMIT" to "b".repeat(64), "FROZEN_FIXTURE_MANIFEST" to commit)) {
            val failure = runCatching {
                GitHubTaskContextBundle.build("owner/repository", sha, "work", files(), "repair receipt", sourceIdentityKind = kind, load = { error("Invalid identities must not fetch source") })
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test fun semanticPolicyFallbackRemainsExplicit() = runBlocking {
        val result = GitHubTaskContextBundle.build(
            "owner/repository", commit, "work", files(), "repair receipt", GitHubContextVariant.SEMANTIC,
            load = ::source, localRanker = { _, _ -> null }, localRankingFallback = "SESSION_ONLY_MEMORY_POLICY"
        )
        assertEquals(JsonPrimitive(false), result["semantic_applied"])
        assertEquals(JsonPrimitive("SESSION_ONLY_MEMORY_POLICY"), result["semantic_fallback"])
    }

    @Test fun aColdSharedReadBudgetHydratesImplementationAndTestBeforeOptionalDocuments() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = build(GitHubContextVariant.TASK) { path ->
            attempts += path
            source(path).takeIf { attempts.size <= 3 }
        }
        assertEquals(listOf(GitHubTaskContextBundle.FEATURE_MAP, implementation, test), attempts.take(3))
        val paths = (result["selected_paths"] as JsonArray).toSet()
        assertTrue(paths.contains(JsonPrimitive(implementation)))
        assertTrue(paths.contains(JsonPrimitive(test)))
        assertEquals(JsonPrimitive("SOURCE_WINDOWS_PREPARED"), result["source_readiness"])
        assertEquals(JsonPrimitive(false), result["source_complete"])
    }

    @Test fun referencesWithoutSourceRetainExactCandidateIdentitiesWithoutClaimingSourceReadiness() = runBlocking {
        val result = build(GitHubContextVariant.TASK) { path -> source(path).takeUnless { path in setOf(implementation, test) } }
        assertEquals(JsonPrimitive("REFERENCES_ONLY"), result["source_readiness"])
        assertEquals(JsonArray(emptyList()), result["selected_ranges"])
        val candidates = (result["source_read_candidates"] as JsonArray).map { it.jsonObject }.associateBy { it["path"] }
        assertEquals(JsonPrimitive("b".repeat(40)), candidates[JsonPrimitive(implementation)]?.get("blob_sha"))
        assertEquals(JsonPrimitive("c".repeat(40)), candidates[JsonPrimitive(test)]?.get("blob_sha"))
        assertTrue(candidates.values.all { it["source_loaded"] == JsonPrimitive(false) })
        assertEquals(JsonPrimitive(false), result["source_complete"])
    }

    private suspend fun build(variant: GitHubContextVariant, query: String = "repairReceipt", load: suspend (String) -> GitHubContextSource? = ::source): JsonObject =
        GitHubTaskContextBundle.build("owner/repository", commit, "work", files(), query, variant, 24_000, load = load)

    private fun files() = listOf(
        GitHubContextFile(implementation, "b".repeat(40), symbols = "repair receipt"),
        GitHubContextFile(test, "c".repeat(40)),
        GitHubContextFile(guide, "d".repeat(40)),
        GitHubContextFile(GitHubTaskContextBundle.FEATURE_MAP, "e".repeat(40))
    )

    private fun source(path: String): GitHubContextSource? = files().singleOrNull { it.path == path }?.let {
        GitHubContextSource(
            path,
            it.blobSha,
            when (path) {
                implementation -> code
                test -> "package sample\nclass RepairStoreTest { fun repairReceiptTest() = RepairStore().repairReceipt() }"
                guide -> "# Repository guide\nGeneral introduction.\n## Repair receipts\nA receipt belongs to one immutable source identity.\n## Unrelated\nOther details."
                else -> manifest("b".repeat(40))
            }
        )
    }

    private fun manifest(sourceBlob: String): String = buildJsonObject {
        put("schema_version", 1)
        put(
            "file_blobs",
            buildJsonObject {
                put(implementation, buildJsonObject { put("blob_sha", sourceBlob) })
                put(test, buildJsonObject { put("blob_sha", "c".repeat(40)) })
                put(guide, buildJsonObject { put("blob_sha", "d".repeat(40)) })
            }
        )
        put(
            "features",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "repair")
                        put("title", "Repair receipts")
                        put(
                            "routing",
                            buildJsonObject {
                                put("entry", implementation)
                                put("role", "evidence")
                                put("proof", "Bound receipt")
                                put("admission", "Selected repository")
                                put("decline", "No receipt source")
                            }
                        )
                        put("terms", JsonArray(listOf(JsonPrimitive("repairReceipt"))))
                        put("paths", JsonArray(listOf(JsonPrimitive(implementation))))
                        put("tests", JsonArray(listOf(JsonPrimitive(test))))
                        put("documents", JsonArray(listOf(JsonPrimitive(guide))))
                        put("commands", JsonArray(listOf(JsonPrimitive("./gradlew test"))))
                        put("invariants", JsonArray(listOf(JsonPrimitive("A repair receipt belongs to its selected source."))))
                    }
                )
            )
        )
    }.toString()
}
