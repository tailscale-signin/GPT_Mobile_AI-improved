package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.github.GitHubPhoneBenchmarkApi
import io.ktor.http.HttpMethod
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkPhoneGitHubTest {
    @Test fun directTransportAndConnectionAreFrozenAndLaneRestricted() {
        val fixture = PhoneFixture()
        assertTrue(fixture.plan.valid())
        assertFalse(fixture.plan.copy(githubConnectionUid = "other").valid())
        assertFalse(fixture.plan.copy(lane = BenchmarkEngineeringLane.EXECUTABLE_CODING).frozen().valid())
        assertFalse(fixture.plan.copy(githubConnectionUid = null).frozen().valid())
        assertFalse(BenchmarkEngineeringRegistry.preview(BenchmarkEngineeringLane.LIVE_GITHUB, externalTransport = BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS).requiresGateway)
        assertFalse(BenchmarkEngineeringBudget(maxModelRounds = 1, maxRepairAttempts = 2).valid())
    }

    @Test fun pinnedDescriptorsAndTemplateMustMatchTheirRawPublishedBytes() {
        val f = PhoneFixture()
        assertEquals(f.taskId, BenchmarkPhoneGitHubContract.descriptor(f.descriptor, f.taskId, f.catalog, f.plan).phoneText("task_id"))
        rejects { BenchmarkPhoneGitHubContract.descriptor(f.descriptor + " ", f.taskId, f.catalog, f.plan) }
        rejects { BenchmarkPhoneGitHubContract.template(f.template + " ", f.catalog, f.plan) }
        val invalid = JsonObject(f.catalog + ("bundleDigest" to JsonPrimitive("0".repeat(64))))
        rejects { BenchmarkPhoneGitHubContract.catalog(invalid.toString(), f.plan) }
        assertEquals("{\"a\":\"\\u00e9\\ud83d\\ude00\",\"z\":1}", BenchmarkPhoneGitHubContract.canonical(Json.parseToJsonElement("{\"z\":1,\"a\":\"é😀\"}")))
    }

    @Test fun successfulPublicationUsesOnlyTwoFilesAndSameIdCannotPublishAgain() = runBlocking {
        val f = PhoneFixture()
        val transport = f.open()
        assertEquals("NOT_OBSERVED", transport.request("?request_id=${f.requestId}").phoneText("submissionState"))
        val result = transport.request("evaluate", f.body())
        assertEquals("COMPLETED", result.phoneText("state"))
        assertEquals(true, (result["cases"] as JsonArray).single().jsonObject["liveGitHubReceipt"]!!.jsonObject.phoneBoolean("remoteReadbackVerified"))
        val source = (result["cases"] as JsonArray).single().jsonObject["liveGitHubReceipt"]!!.jsonObject
        assertEquals(53L, source.phoneLong("artifactId"))
        assertEquals(42L, source.phoneLong("workflowRunId"))
        assertEquals(benchmarkHash(f.catalog.toString()), source.phoneText("catalogDigest"))
        assertEquals(f.id, source.phoneText("submissionId"))
        assertEquals(source, f.journal.rows.values.single()["remoteReceipt"])
        assertEquals(listOf("/git/trees", "/git/commits", "/git/refs"), f.io.writes.map { it.first.removePrefix(f.root) })
        transport.request("evaluate", f.body())
        assertEquals(3, f.io.writes.size)
        val changed = JsonObject(f.body() + mapOf("proposal" to JsonPrimitive("different"), "proposal_digest" to JsonPrimitive(benchmarkHash("different"))))
        try {
            transport.request("evaluate", changed)
            fail("A request ID cannot identify another candidate")
        } catch (_: IllegalArgumentException) { }
        assertEquals(3, f.io.writes.size)
    }

    @Test fun lostPublicationResponseIsReconciledWithoutAnotherMutation() = runBlocking {
        val f = PhoneFixture()
        f.io.loseRefResponse = true
        val transport = f.open()
        try {
            transport.request("evaluate", f.body())
            fail("Expected lost response")
        } catch (_: IllegalStateException) { }
        val recovered = f.open().request("?request_id=${f.requestId}")["run"]!!.jsonObject
        assertEquals("COMPLETED", recovered.phoneText("state"))
        assertEquals(3, f.io.writes.size)
    }

    @Test fun absentUncertainPublicationCannotBeDeclaredCanceledOrResubmitted() = runBlocking {
        val f = PhoneFixture()
        f.io.loseRefResponse = true
        f.io.acceptRef = false
        var transport = f.open()
        try {
            transport.request("evaluate", f.body())
            fail("Expected lost response")
        } catch (_: IllegalStateException) { }
        transport = f.open()
        val canceled = transport.request("cancel-request", buildJsonObject { put("request_id", f.requestId) })["run"]!!.jsonObject
        assertEquals("RUNNING", canceled.phoneText("state"))
        assertEquals("CANCELLATION_UNCONFIRMED", canceled["failure"]!!.jsonObject.phoneText("code"))
        assertEquals("RUNNING", transport.request("evaluate", f.body()).phoneText("state"))
        assertEquals(3, f.io.writes.size)
    }

    @Test fun tombstoneBeforeDispatchPreventsEverySubsequentWrite() = runBlocking {
        val f = PhoneFixture()
        val transport = f.open()
        assertEquals("REMOVED", transport.request("cancel-request", buildJsonObject { put("request_id", f.requestId) }).phoneText("submissionState"))
        assertEquals("CANCELLED", transport.request("evaluate", f.body()).phoneText("state"))
        assertTrue(f.io.writes.isEmpty())
    }

    @Test fun observedRefRejectionRetiresAdmissionWithoutPretendingAnUnknownResponseWasRejected() = runBlocking {
        val f = PhoneFixture()
        f.io.rejectRef = true
        val transport = f.open()
        try {
            transport.request("evaluate", f.body())
            fail("Expected explicit rejection")
        } catch (_: BenchmarkPhoneMutationRejected) { }
        assertEquals("REMOVED", transport.request("cancel-request", buildJsonObject { put("request_id", f.requestId) }).phoneText("submissionState"))
        assertEquals("CANCELLED", transport.request("evaluate", f.body()).phoneText("state"))
        assertEquals(3, f.io.writes.size)
    }

    @Test fun restoredRemoteJobIsReadAndCanceledEvenWithoutItsCachedLocalBody() = runBlocking {
        val f = PhoneFixture()
        f.io.runCompleted = false
        f.open().request("evaluate", f.body())
        f.journal.rows.clear()
        val result = f.open().request("cancel-request", buildJsonObject { put("request_id", f.requestId) })["run"]!!.jsonObject
        assertEquals("CANCELLED", result.phoneText("state"))
        assertEquals("${f.root}/actions/runs/42/cancel", f.io.writes.last().first)
        assertEquals(4, f.io.writes.size)
    }

    @Test fun queuedWorkflowCancellationUsesOnlyItsBoundRunAndPollingDoesNotRepeatSourceReads() = runBlocking {
        val f = PhoneFixture()
        f.io.runCompleted = false
        val transport = f.open()
        val accepted = transport.request("evaluate", f.body())
        assertEquals("RUNNING", accepted.phoneText("state"))
        val before = f.io.requests.size
        transport.request(accepted.phoneText("id"))
        assertEquals(listOf("${f.root}/actions/runs/42"), f.io.requests.drop(before))
        val result = transport.request("cancel-request", buildJsonObject { put("request_id", f.requestId) })["run"]!!.jsonObject
        assertEquals("CANCELLED", result.phoneText("state"))
        assertEquals(1, f.io.writes.count { it.first.endsWith("/cancel") })
    }

    @Test fun redCiMissingArtifactAndWrongProposalNeverBecomeModelFailures() = runBlocking {
        for (mode in listOf("red", "missing", "proposal", "generation", "attempt")) {
            val f = PhoneFixture()
            when (mode) {
                "red" -> f.io.conclusion = "failure"
                "missing" -> f.io.artifactPresent = false
                else -> f.io.corruptProof = mode
            }
            val result = f.open().request("evaluate", f.body())
            assertEquals(mode, "FAILED", result.phoneText("state"))
            assertEquals(mode, 0, (result["cases"] as JsonArray).size)
            assertEquals("INFRASTRUCTURE", result["failure"]!!.jsonObject.phoneText("failureDomain"))
        }
    }

    @Test fun lostCancelResponseIsDurablyBoundAndNeverPostedAgainAfterRestart() = runBlocking {
        val f = PhoneFixture()
        f.io.runCompleted = false
        f.io.loseCancelResponse = true
        val transport = f.open()
        transport.request("evaluate", f.body())
        val cancel = buildJsonObject { put("request_id", f.requestId) }
        assertEquals("RUNNING", transport.request("cancel-request", cancel)["run"]!!.jsonObject.phoneText("state"))
        assertEquals(1, f.io.writes.count { it.first.endsWith("/cancel") })
        val restored = f.open()
        repeat(3) {
            val observed = restored.request("cancel-request", cancel)["run"]!!.jsonObject
            assertEquals("RUNNING", observed.phoneText("state"))
            assertEquals("CANCELLATION_UNCONFIRMED", observed["failure"]!!.jsonObject.phoneText("code"))
        }
        assertEquals(1, f.io.writes.count { it.first.endsWith("/cancel") })
        assertTrue(f.journal.rows.values.single().phoneBoolean("cancelDispatched") == true)
        assertEquals(42L, f.journal.rows.values.single().phoneLong("cancelRunId"))
        assertEquals(1L, f.journal.rows.values.single().phoneLong("cancelRunAttempt"))
        f.io.runCompleted = true
        f.io.conclusion = "cancelled"
        assertEquals("CANCELLED", restored.request("cancel-request", cancel)["run"]!!.jsonObject.phoneText("state"))
        assertEquals(1, f.io.writes.count { it.first.endsWith("/cancel") })
    }

    @Test fun typedBoundModelFailureRemainsDistinctFromInfrastructure() = runBlocking {
        val f = PhoneFixture()
        f.io.modelPassed = false
        val result = f.open().request("evaluate", f.body())
        assertEquals("COMPLETED", result.phoneText("state"))
        val row = (result["cases"] as JsonArray).single().jsonObject
        assertEquals("FAILED", row.phoneText("status"))
        assertEquals(false, row.phoneBoolean("resolved"))
        assertEquals(true, row["liveGitHubReceipt"]!!.jsonObject.phoneBoolean("remoteReadbackVerified"))
    }

    @Test fun unrelatedWorkflowOrChangedCommitDeltaCannotProduceEvidence() = runBlocking {
        for (mode in listOf("workflow", "delta", "branch")) {
            val f = PhoneFixture()
            f.io.corruptRemote = mode
            val result = runCatching { f.open().request("evaluate", f.body()) }.getOrNull()
            assertTrue(result == null || result.phoneText("state") == "RUNNING")
            assertEquals(0, f.io.artifactReads)
        }
    }

    @Test fun nativeTransportRejectsBroadMutationsAndResultArchivesAreStrictlyBounded() {
        val repository = "owner/disposable"
        for (path in listOf("/git/refs/heads/main", "/actions/runs/42/rerun", "/pulls")) {
            rejects { GitHubPhoneBenchmarkApi.validate(repository, "/repos/$repository$path", HttpMethod.Post, JsonObject(emptyMap())) }
        }
        rejects { GitHubPhoneBenchmarkApi.validate(repository, "/repos/other/repo/git/refs", HttpMethod.Post, JsonObject(emptyMap())) }
        val bytes = archive(mapOf("result.json" to "{\"ok\":true}"))
        assertEquals("{\"ok\":true}", GitHubPhoneBenchmarkApi.archive(bytes, sha(bytes)))
        rejects { GitHubPhoneBenchmarkApi.archive(bytes, "0".repeat(64)) }
        val extra = archive(mapOf("result.json" to "{}", "secret.txt" to "unrelated"))
        rejects { GitHubPhoneBenchmarkApi.archive(extra, sha(extra)) }
        val expansion = archive(mapOf("result.json" to "x".repeat(256 * 1024 + 1)))
        rejects { GitHubPhoneBenchmarkApi.archive(expansion, sha(expansion)) }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected a rejected contract")
        } catch (expected: IllegalArgumentException) { } catch (expected: IllegalStateException) { }
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}

private class PhoneFixture {
    val taskId = "pagination-1"
    val requestId = "eng_0123456789abcdef0123456789abcdef"
    val template = "name: __BENCHMARK_SUBMISSION_ID__\nbase: __BENCHMARK_BASE_SHA__\nbranch: __BENCHMARK_BRANCH__\n"
    private val environment = buildJsonObject {
        put("image", "python@sha256:${"a".repeat(64)}")
        put("taskPack", "python")
    }
    val environmentDigest = benchmarkHash(BenchmarkPhoneGitHubContract.canonical(environment))
    val grader = benchmarkHash("independent grader")
    val fixtureDigest = benchmarkHash("frozen task")
    val descriptor = buildJsonObject {
        put("task_id", taskId)
        put("id", taskId)
        put("taskPack", "python")
        put("seed", 17)
        put("taskDigest", fixtureDigest)
        put("graderDigest", grader)
        put("environmentDigest", environmentDigest)
        put("prompt", "Repair the frozen source.")
        put("outputSchema", buildJsonObject { put("type", "object") })
    }.toString()
    private val manifest = buildJsonObject {
        put(".benchmark-phone/fixtures/python/17/$taskId.json", benchmarkHash(descriptor))
        put(".benchmark-phone/workflows/python.yml", benchmarkHash(template))
    }
    val catalog = buildJsonObject {
        put("schema", "gateway.phone-benchmark-catalog.v1")
        put("sourceCommit", "f".repeat(40))
        put("bundleManifest", manifest)
        put("bundleDigest", benchmarkHash(BenchmarkPhoneGitHubContract.canonical(manifest)))
        put(
            "packs",
            buildJsonObject {
                put(
                    "python",
                    buildJsonObject {
                        put("environment", environment)
                        put("environmentDigest", environmentDigest)
                        put("graderDigest", grader)
                        put("seeds", JsonArray(listOf(JsonPrimitive(17))))
                        put("presets", buildJsonObject { put("quick", JsonArray(listOf(JsonPrimitive(taskId)))) })
                        put("workflowTemplatePath", ".benchmark-phone/workflows/python.yml")
                        put("workflowTemplateDigest", benchmarkHash(template))
                        put("fixturePathTemplate", ".benchmark-phone/fixtures/python/{seed}/{task_id}.json")
                        put(
                            "tasksBySeed",
                            buildJsonObject {
                                put(
                                    "17",
                                    buildJsonObject {
                                        put(
                                            taskId,
                                            buildJsonObject {
                                                put("descriptorDigest", benchmarkHash(descriptor))
                                                put("fixtureDigest", fixtureDigest)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }
    val plan = BenchmarkEngineeringTestFixtures().plan.copy(
        lane = BenchmarkEngineeringLane.LIVE_GITHUB,
        externalTransport = BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS,
        githubConnectionUid = "github-selected",
        repositoryFullName = "owner/disposable",
        baseSha = "b".repeat(40),
        externalFixtureDigests = mapOf(taskId to fixtureDigest),
        graderDigest = grader
    ).frozen()
    val root = "/repos/${plan.repositoryFullName}"
    val id = BenchmarkPhoneGitHubContract.submissionId(requestId)
    val branch = BenchmarkPhoneGitHubContract.branch(plan, id)
    val revision = "c".repeat(40)
    class MemoryJournal : BenchmarkPhoneGitHubJournal {
        val rows = mutableMapOf<String, JsonObject>()
        override suspend fun read(key: String) = rows[key]
        override suspend fun update(key: String, transform: (JsonObject?) -> JsonObject) = transform(rows[key]).also { rows[key] = it }
    }
    val journal = MemoryJournal()
    val io = FakeIo()
    suspend fun open() = BenchmarkPhoneGitHubTransport.open(plan, io, journal, Mutex(), benchmarkHash("selected GitHub authority"))
    fun body() = buildJsonObject {
        put("request_id", requestId)
        put("task_id", taskId)
        put("task_pack", "python")
        put("seed", 17)
        put("fixture_digest", fixtureDigest)
        put("grader_digest", grader)
        put("environment_digest", environmentDigest)
        put("repository", requireNotNull(plan.repositoryFullName))
        put("base_sha", requireNotNull(plan.baseSha))
        put("cohort", plan.cohortId)
        put("proposal", "{}")
        put("proposal_digest", benchmarkHash("{}"))
        put(
            "generation_receipt",
            buildJsonObject {
                put("profile_uid", plan.profileUids.first())
                put("profile_revision", requireNotNull(plan.profileConfigKeys[plan.profileUids.first()]))
                put("model", "selected-model")
            }
        )
    }

    inner class FakeIo : BenchmarkPhoneGitHubIo {
        override var requestCount = 0
        val writes = mutableListOf<Pair<String, JsonObject>>()
        val requests = mutableListOf<String>()
        var published = false
        var acceptRef = true
        var loseRefResponse = false
        var rejectRef = false
        var loseCancelResponse = false
        var runCompleted = true
        var conclusion = "success"
        var artifactPresent = true
        var artifactReads = 0
        var modelPassed = true
        var corruptProof = ""
        var corruptRemote = ""
        private val submittedFiles = mutableMapOf<String, String>()
        override suspend fun request(path: String, body: JsonObject?): JsonObject {
            requestCount++
            requests += path
            if (body != null) {
                GitHubPhoneBenchmarkApi.validate(requireNotNull(plan.repositoryFullName), path, HttpMethod.Post, body)
                writes += path to body
                return when (path.removePrefix(root)) {
                    "/git/trees" -> {
                        (body["tree"] as JsonArray).forEach {
                            val row = it.jsonObject
                            submittedFiles[row.phoneText("path")] = row.phoneText("content")
                        }
                        buildJsonObject { put("sha", "d".repeat(40)) }
                    }
                    "/git/commits" -> buildJsonObject { put("sha", revision) }
                    "/git/refs" -> {
                        if (rejectRef) throw BenchmarkPhoneMutationRejected()
                        published = acceptRef
                        if (loseRefResponse) error("lost response")
                        buildJsonObject { put("ref", "refs/heads/$branch") }
                    }
                    "/actions/runs/42/cancel" -> {
                        if (loseCancelResponse) error("lost cancellation response")
                        runCompleted = true
                        conclusion = "cancelled"
                        JsonObject(emptyMap())
                    }
                    else -> error("Unexpected mutation $path")
                }
            }
            GitHubPhoneBenchmarkApi.validate(requireNotNull(plan.repositoryFullName), path, HttpMethod.Get, null)
            val relative = path.removePrefix(root)
            return when {
                relative.isEmpty() -> buildJsonObject {
                    put("full_name", requireNotNull(plan.repositoryFullName))
                    put("archived", false)
                    put("permissions", buildJsonObject { put("push", true) })
                }
                relative.startsWith("/contents/") -> {
                    val file = relative.removePrefix("/contents/").substringBefore('?')
                    val value = if (relative.endsWith("ref=${plan.baseSha}")) {
                        when (file) {
                            BenchmarkPhoneGitHubContract.CATALOG -> catalog.toString()
                            ".benchmark-phone/workflows/python.yml" -> template
                            ".benchmark-phone/fixtures/python/17/$taskId.json" -> descriptor
                            else -> error("Unexpected base file $file")
                        }
                    } else {
                        submittedFiles.getValue(file)
                    }
                    buildJsonObject {
                        put("type", "file")
                        put("encoding", "base64")
                        put("size", value.encodeToByteArray().size)
                        put("content", Base64.getEncoder().encodeToString(value.encodeToByteArray()))
                    }
                }
                relative.startsWith("/git/ref/heads/") -> if (!published) buildJsonObject { put("owned_ref_absent", true) } else buildJsonObject { put("object", buildJsonObject { put("sha", if (corruptRemote == "branch") "e".repeat(40) else revision) }) }
                relative == "/git/commits/${plan.baseSha}" -> buildJsonObject {
                    put("sha", requireNotNull(plan.baseSha))
                    put("tree", buildJsonObject { put("sha", "a".repeat(40)) })
                }
                relative == "/git/commits/$revision" -> buildJsonObject {
                    put("sha", revision)
                    put("parents", JsonArray(listOf(buildJsonObject { put("sha", requireNotNull(plan.baseSha)) })))
                }
                relative.startsWith("/git/trees/") -> buildJsonObject {
                    put("truncated", false)
                    put("tree", JsonArray(emptyList()))
                }
                relative.startsWith("/compare/") -> buildJsonObject {
                    put("total_commits", 1)
                    put(
                        "files",
                        JsonArray(
                            (submittedFiles.keys + if (corruptRemote == "delta") setOf("unrelated.txt") else emptySet()).map {
                                buildJsonObject {
                                    put("filename", it)
                                    put("status", "added")
                                }
                            }
                        )
                    )
                }
                relative.startsWith("/actions/runs?") -> list("workflow_runs", listOf(run()))
                relative == "/actions/runs/42" -> run()
                relative == "/actions/runs/42/attempts/1/jobs?per_page=100" -> list(
                    "jobs",
                    listOf(
                        buildJsonObject {
                            put("name", BenchmarkPhoneGitHubContract.JOB)
                            put("status", "completed")
                            put("conclusion", "success")
                            put("run_id", 42)
                            put(
                                "steps",
                                JsonArray(
                                    BenchmarkPhoneGitHubContract.REQUIRED_STEPS.map {
                                        buildJsonObject {
                                            put("name", it)
                                            put("status", "completed")
                                            put("conclusion", "success")
                                        }
                                    }
                                )
                            )
                        }
                    )
                )
                relative == "/actions/runs/42/artifacts?per_page=100" -> list(
                    "artifacts",
                    if (!artifactPresent) {
                        emptyList()
                    } else {
                        listOf(
                            buildJsonObject {
                                put("id", 53)
                                put("name", "phone-benchmark-$id")
                                put("expired", false)
                                put("digest", "sha256:${"f".repeat(64)}")
                                put("size_in_bytes", 123)
                                put(
                                    "workflow_run",
                                    buildJsonObject {
                                        put("id", 42)
                                        put("head_sha", revision)
                                        put("head_branch", branch)
                                    }
                                )
                            }
                        )
                    }
                )
                else -> error("Unexpected read $path")
            }
        }
        override suspend fun artifact(id: Long, digest: String): String {
            requestCount++
            artifactReads++
            val submission = Json.parseToJsonElement(submittedFiles.getValue(BenchmarkPhoneGitHubContract.submissionPath(this@PhoneFixture.id))).jsonObject
            val result = buildJsonObject {
                put("schema", "gateway.phone-benchmark-result.v1")
                mapOf("requestId" to "request_id", "taskId" to "task_id", "taskPack" to "task_pack", "seed" to "seed", "repository" to "repository", "branch" to "branch", "baseSha" to "base_sha", "workflowPath" to "workflow_path", "proposalDigest" to "proposal_digest", "fixtureDigest" to "fixture_digest", "graderDigest" to "grader_digest", "environmentDigest" to "environment_digest", "bundleDigest" to "bundle_digest", "generationReceipt" to "generation_receipt").forEach { (to, from) -> put(to, submission.getValue(from)) }
                put("testedRevision", revision)
                put("publishedRevision", revision)
                put("workflowRunId", 42)
                put("workflowRunAttempt", if (corruptProof == "attempt") 2 else 1)
                put("liveGitHub", "OBSERVED")
                put("outcomeKnown", true)
                put("failureDomain", if (modelPassed) "" else "MODEL")
                val row = buildJsonObject {
                    put("id", taskId)
                    put("status", if (modelPassed) "PASSED" else "FAILED")
                    put("qualified", true)
                    put("resolved", modelPassed)
                    put("graded", true)
                    put("executed", true)
                }
                row.filterKeys { it != "id" }.forEach { (key, value) -> put(key, value) }
                put("case", row)
                if (corruptProof == "proposal") put("proposalDigest", benchmarkHash("other proposal"))
                if (corruptProof == "generation") put("generationReceipt", buildJsonObject { put("profile_uid", "other-model") })
            }
            return JsonObject(result + ("receiptDigest" to JsonPrimitive(benchmarkHash(BenchmarkPhoneGitHubContract.canonical(result))))).toString()
        }
        private fun run() = buildJsonObject {
            put("id", 42)
            put("run_attempt", 1)
            put("event", "push")
            put("head_sha", revision)
            put("head_branch", branch)
            put("path", if (corruptRemote == "workflow") ".github/workflows/unrelated.yml" else BenchmarkPhoneGitHubContract.workflowPath(id))
            put("status", if (runCompleted) "completed" else "in_progress")
            put("conclusion", conclusion)
            put("repository", buildJsonObject { put("full_name", requireNotNull(plan.repositoryFullName)) })
        }
        private fun list(key: String, rows: List<JsonObject>) = buildJsonObject {
            put("total_count", rows.size)
            put(key, JsonArray(rows))
        }
    }
}
