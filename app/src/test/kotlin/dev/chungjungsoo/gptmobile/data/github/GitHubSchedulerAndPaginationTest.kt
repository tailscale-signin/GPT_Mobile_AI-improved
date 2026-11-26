package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubSchedulerAndPaginationTest {
    private val head = "a".repeat(40)
    private fun args(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        extra()
    }

    @Test fun immutableReadsJoinAndCancellationReleasesQueueForVisiblePriority() = runTest {
        val scheduler = GitHubRequestScheduler(concurrency = 1, maxQueued = 3)
        val held = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            scheduler.execute("blob:one") {
                order += "first"
                held.await()
                "same bytes"
            }
        }
        runCurrent()
        val joined = async { scheduler.execute<String>("blob:one") { error("Duplicate immutable dispatch") } }
        val cancelled = async { scheduler.execute<Unit>(priority = 20) { error("Cancelled work must not execute") } }
        val background = async { scheduler.execute(priority = -1) { order += "background" } }
        val visible = async { scheduler.execute(priority = 10) { order += "visible" } }
        runCurrent()
        cancelled.cancelAndJoin()
        held.complete(Unit)
        assertEquals(first.await(), joined.await())
        visible.await()
        background.await()
        assertEquals(listOf("first", "visible", "background"), order)
        assertEquals(1, scheduler.metrics()["singleflight_joins"]!!.jsonPrimitive.intOrNull)
        assertEquals(0, scheduler.metrics()["active_requests"]!!.jsonPrimitive.intOrNull)
        assertEquals(0, scheduler.metrics()["queued_requests"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun primaryQuotasAreResourceScopedButSecondaryCooldownIsAccountWide() {
        val now = 1_700_000_000L
        val quota = GitHubRateLimitManager()
        quota.record(headersOf("X-RateLimit-Resource" to listOf("search"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf((now + 60).toString())), nowMillis = now * 1000)
        assertTrue(runCatching { quota.requireAvailable("search", now) }.isFailure)
        quota.requireAvailable("core", now)
        quota.requireAvailable("graphql", now)
        quota.record(headersOf("Retry-After", "30"), 429, nowMillis = now * 1000)
        assertTrue(runCatching { quota.requireAvailable("core", now) }.isFailure)
        assertTrue(runCatching { quota.requireAvailable("core", now + 29) }.isFailure)
        assertTrue(runCatching { quota.requireAvailable("graphql", now + 29) }.isFailure)
        quota.requireAvailable("core", now + 30)
        quota.requireAvailable("graphql", now + 30)
        assertTrue(runCatching { quota.requireAvailable("search", now + 30) }.isFailure)
        val date = java.time.Instant.ofEpochSecond(now + 60).atZone(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        quota.record(headersOf("Retry-After", date), 403, nowMillis = now * 1000)
        assertTrue(quota.retryDelayMillis(now) > 0)
    }

    @Test fun mutableInvalidationPreservesImmutableBodiesAndOtherRepositories() {
        val cache = GitHubResponseCache()
        val value = buildJsonObject { put("x", 1) }
        cache.put("/repos/a/r/pulls/1", "pr", value)
        cache.put("build-catalog:/repos/a/r/releases", null, value, allowWithoutEtag = true)
        cache.put("/repos/a/r/git/blobs/$head", null, value, immutable = true)
        cache.put("/repos/b/r/pulls/1", "pr2", value)
        cache.putDecodedBlob(head, "body")
        cache.invalidateMutable("/repos/a/r")
        assertEquals(null, cache.get("/repos/a/r/pulls/1"))
        assertEquals(null, cache.get("build-catalog:/repos/a/r/releases"))
        assertNotNull(cache.get("/repos/a/r/git/blobs/$head"))
        assertNotNull(cache.get("/repos/b/r/pulls/1"))
        assertEquals("body", cache.getDecodedBlob(head))
    }

    @Test fun prContextReaches125FilesAnd45ChecksWithImmutableHeadFence() = runTest {
        var mutableHead = head
        val filePages = mutableListOf<Int>()
        val checkPages = mutableListOf<Int>()
        val http = HttpClient(
            MockEngine { request ->
                val page = request.url.parameters["page"]?.toInt() ?: 1
                assertTrue(request.url.parameters["per_page"] == null || request.url.parameters["per_page"] == "30")
                when {
                    request.url.encodedPath.endsWith("/pulls/7") -> respond("""{"number":7,"changed_files":125,"head":{"sha":"$mutableHead"},"base":{"sha":"${"b".repeat(40)}"}}""")
                    request.url.encodedPath.endsWith("/files") -> {
                        filePages += page
                        respond(
                            JsonArray(
                                ((page - 1) * 30 until minOf(page * 30, 125)).map {
                                    buildJsonObject {
                                        put("filename", "src/$it.kt")
                                        put("sha", head)
                                        put("patch", "x".repeat(4000))
                                        put("status", "modified")
                                    }
                                }
                            ).toString()
                        )
                    }
                    request.url.encodedPath.endsWith("/check-runs") -> {
                        checkPages += page
                        respond(
                            buildJsonObject {
                                put("total_count", 45)
                                put(
                                    "check_runs",
                                    JsonArray(
                                        ((page - 1) * 30 until minOf(page * 30, 45)).map {
                                            buildJsonObject {
                                                put("id", it)
                                                put("status", "completed")
                                                put("conclusion", "success")
                                            }
                                        }
                                    )
                                )
                            }.toString()
                        )
                    }
                    else -> error(request.url.toString())
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("pagination-token", http)
            var next = args {
                put("pull_number", 7)
                put("include_patches", true)
            }
            val files = mutableSetOf<String>()
            var first: JsonObject? = null
            repeat(5) { index ->
                val response = client.execute("pr_context", next).jsonObject
                if (first == null) first = response
                if (index > 0) assertFalse("File continuations must not resend checks", "checks" in response)
                response["files"]!!.jsonArray.forEach { row ->
                    val file = row.jsonObject
                    files += file["filename"]!!.jsonPrimitive.content
                    assertTrue(file["patch"]!!.jsonPrimitive.content.encodeToByteArray().size <= 2000)
                    assertTrue(file["patch_truncated"]!!.jsonPrimitive.booleanOrNull == true)
                }
                if (index < 4) {
                    next = JsonObject(args() + response["files_continuation"]!!.jsonObject)
                } else {
                    assertFalse(response["has_more"]!!.jsonPrimitive.booleanOrNull == true)
                }
            }
            assertEquals(125, files.size)
            val moreChecks = client.execute("pr_context", JsonObject(args() + first!!["checks_continuation"]!!.jsonObject)).jsonObject
            assertEquals(30, first!!["checks"]!!.jsonObject["returned"]!!.jsonPrimitive.intOrNull)
            assertEquals(15, moreChecks["checks"]!!.jsonObject["returned"]!!.jsonPrimitive.intOrNull)
            assertFalse(moreChecks["checks"]!!.jsonObject["has_more"]!!.jsonPrimitive.booleanOrNull == true)
            assertFalse("Check continuations must not resend files", "files" in moreChecks)
            assertEquals(listOf(1, 2, 3, 4, 5), filePages)
            assertEquals(listOf(1, 2), checkPages)
            mutableHead = "c".repeat(40)
            assertTrue(runCatching { client.execute("pr_context", next) }.exceptionOrNull()?.message?.contains("head changed", true) == true)
        } finally {
            http.close()
        }
    }

    @Test fun prFilesAreRejectedWhenHeadOrBaseChangesDuringTheMutableRead() = runTest {
        for (changedSide in listOf("head", "base")) {
            var metadataReads = 0
            var checkReads = 0
            val base = "b".repeat(40)
            val http = HttpClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/pulls/7") -> {
                            metadataReads++
                            val observedHead = if (changedSide == "head" && metadataReads > 1) "c".repeat(40) else head
                            val observedBase = if (changedSide == "base" && metadataReads > 1) "d".repeat(40) else base
                            respond("""{"number":7,"changed_files":1,"head":{"sha":"$observedHead"},"base":{"sha":"$observedBase"}}""")
                        }
                        request.url.encodedPath.endsWith("/files") -> respond("""[{"filename":"changed.kt","sha":"$head"}]""")
                        request.url.encodedPath.endsWith("/check-runs") -> {
                            checkReads++
                            respond("""{"check_runs":[],"total_count":0}""")
                        }
                        else -> error(request.url.toString())
                    }
                }
            )
            try {
                val failure = runCatching { GitHubWorkspaceClient("moving-pr-$changedSide", http).execute("pr_context", args { put("pull_number", 7) }) }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("while files were loading"))
                assertEquals(2, metadataReads)
                assertEquals(0, checkReads)
            } finally {
                http.close()
            }
        }
    }

    @Test fun prFileContinuationRejectsChangedBaseEvenWhenHeadIsUnchanged() = runTest {
        var base = "b".repeat(40)
        var fileReads = 0
        val http = HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/pulls/7") -> respond("""{"number":7,"changed_files":31,"head":{"sha":"$head"},"base":{"sha":"$base"}}""")
                    request.url.encodedPath.endsWith("/files") -> {
                        fileReads++
                        respond(JsonArray((1..30).map { buildJsonObject { put("filename", "file-$it") } }).toString())
                    }
                    else -> error(request.url.toString())
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("retargeted-pr", http)
            val first = client.execute(
                "pr_context",
                args {
                    put("pull_number", 7)
                    put("include_checks", false)
                }
            ).jsonObject
            base = "c".repeat(40)
            val failure = runCatching { client.execute("pr_context", JsonObject(args() + first["files_continuation"]!!.jsonObject)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("base changed"))
            assertEquals(1, fileReads)
        } finally {
            http.close()
        }
    }

    @Test fun fullBoundedCiLogScanFindsLateFailureAndRedirectNeverReceivesToken() = runTest {
        val log = (1..18000).joinToString("\n") { "progress $it still compiling" } + "\nERROR: late compiler failure at end\nBUILD FAILED\n"
        val requests = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                when {
                    request.url.host == "logs.blob.core.windows.net" -> {
                        assertEquals(null, request.headers[HttpHeaders.Authorization])
                        respond(log)
                    }
                    request.url.encodedPath.endsWith("/logs") -> {
                        assertEquals("Bearer ci-token", request.headers[HttpHeaders.Authorization])
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://logs.blob.core.windows.net/job7?sig=secret"))
                    }
                    request.url.encodedPath.endsWith("/actions/jobs/7") -> respond("""{"id":7,"run_id":8,"status":"completed"}""")
                    request.url.encodedPath.endsWith("/actions/runs/8") -> respond("""{"id":8,"head_sha":"$head"}""")
                    request.url.encodedPath.endsWith("/commits/$head") -> respond("""{"files":[{"filename":"app/Compiler.kt"}]}""")
                    else -> error(request.url.toString())
                }
            }
        ) { followRedirects = false }
        try {
            val result = GitHubWorkspaceClient("ci-token", http).execute(
                "ci_failure_digest",
                args {
                    put("job_id", 7)
                    put("expected_head_sha", head)
                }
            ).jsonObject
            assertTrue(result["log_complete"]!!.jsonPrimitive.booleanOrNull == true)
            assertTrue(result["failure_lines"]!!.jsonArray.any { it.jsonPrimitive.intOrNull!! > 18000 })
            assertTrue(result["excerpts"].toString().contains("late compiler failure"))
            assertFalse(result.toString().contains("sig=secret"))
            assertTrue(result.toString().length < 30000)
            assertEquals(5, requests.size)
        } finally {
            http.close()
        }
    }

    @Test fun failureDigestLinksTheJobsOwnCheckAndJunitReceiptEvenWhenThePrTestedAMerge() = runTest {
        val merge = "b".repeat(40)
        val reportHash = "f".repeat(64)
        val junit = """{"schema_version":1,"run_id":"8","run_attempt":"2","repository":"owner/repo","job":"validate","head_sha":"$head","tested_sha":"$merge","tracked_files_clean":true,"failures":[{"class_name":"second.FooTest","name":"check","outcome":"ERROR","report_sha256":"$reportHash","source_path":"second/FooTest.kt"}]}"""
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { archive ->
                archive.putNextEntry(java.util.zip.ZipEntry("ci-junit.json"))
                archive.write(junit.encodeToByteArray())
                archive.closeEntry()
            }
        }.toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
        val checkUrl = "https://api.github.com/repos/owner/repo/check-runs/19"
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath.removePrefix("/repos/owner/repo")) {
                    "/actions/jobs/7" -> respond("""{"id":7,"run_id":8,"run_attempt":2,"status":"completed","check_run_url":"$checkUrl"}""")
                    "/actions/runs/8/attempts/2" -> respond("""{"id":8,"run_attempt":2,"head_sha":"$head"}""")
                    "/actions/jobs/7/logs" -> respond("error in FooTest.kt:14\nArtifact ci-junit-8-2-validate has been successfully uploaded! Final size is ${zip.size} bytes. Artifact ID is 20")
                    "/commits/$head" -> respond("""{"files":[{"filename":"first/FooTest.kt"},{"filename":"second/FooTest.kt"}]}""")
                    "/check-runs/19" -> respond("""{"id":19,"head_sha":"$head"}""")
                    "/check-runs/19/annotations" -> respond("""[{"path":"first/FooTest.kt","start_line":14,"end_line":14,"message":"compilation error","annotation_level":"failure"}]""")
                    "/actions/artifacts/20" -> respond("""{"id":20,"name":"ci-junit-8-2-validate","size_in_bytes":${zip.size},"expired":false,"digest":"sha256:$hash","workflow_run":{"id":8,"head_sha":"$head"}}""")
                    "/actions/artifacts/20/zip" -> respond(zip)
                    else -> error(request.url.toString())
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("ci-test-evidence-token", http)
            fun request(attempt: Int) = args {
                put("expected_head_sha", head)
                put("job_id", 7)
                put("run_attempt", attempt)
            }
            val result = client.execute("ci_failure_digest", request(2)).jsonObject
            val evidence = result.getValue("diagnosis").jsonArray.map { it.jsonObject }.associateBy { it.getValue("source").jsonPrimitive.content }
            assertEquals("https://github.com/owner/repo/blob/$head/first/FooTest.kt#L14", evidence.getValue("CHECK_RUN_ANNOTATION").getValue("source_url").jsonPrimitive.content)
            assertEquals("https://github.com/owner/repo/blob/$merge/second/FooTest.kt", evidence.getValue("JUNIT_ARTIFACT").getValue("source_url").jsonPrimitive.content)
            assertEquals("https://github.com/owner/repo/actions/runs/8/job/7", evidence.getValue("JUNIT_ARTIFACT").getValue("job_url").jsonPrimitive.content)
            assertEquals("AMBIGUOUS_BASENAME", evidence.getValue("JOB_LOG").getValue("location_status").jsonPrimitive.content)
            assertTrue(evidence.getValue("JOB_LOG").getValue("matching_changed_paths").jsonArray.isEmpty())
            assertEquals(2, result.getValue("expand_with").jsonObject.getValue("run_attempt").jsonPrimitive.intOrNull)
            assertTrue(runCatching { client.execute("ci_failure_digest", request(1)) }.exceptionOrNull()?.message.orEmpty().contains("attempt"))
        } finally {
            http.close()
        }
    }

    @Test fun ciDigestCanFindNewestFailedJobWithoutRunOrJobId() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/actions/runs") -> {
                        assertEquals("work", request.url.parameters["branch"])
                        respond("""{"workflow_runs":[{"id":8,"head_sha":"$head"}]}""")
                    }
                    request.url.encodedPath.endsWith("/jobs") -> respond("""{"jobs":[{"id":7,"run_id":8,"status":"completed","conclusion":"failure"}]}""")
                    request.url.encodedPath.endsWith("/logs") -> respond("build failed: important diagnostic")
                    else -> error(request.url.toString())
                }
            }
        )
        try {
            val result = GitHubWorkspaceClient("ci-latest", http).execute("ci_failure_digest", args { put("ref", "work") }).jsonObject
            assertEquals(7, result["job_id"]!!.jsonPrimitive.intOrNull)
            assertEquals(head, result["head_sha"]!!.jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun ciLogBoundReportsIncompleteWhenRemoteBodyExceedsFourMiB() = runTest {
        val http = HttpClient(MockEngine { respond("x".repeat(4 * 1024 * 1024 + 1)) })
        try {
            val result = GitHubCiLogReader(http, "token", GitHubRequestScheduler(), GitHubResponseCache()).read("/repos/a/r/actions/jobs/1/logs", true)
            assertEquals(4 * 1024 * 1024, result.bytes)
            assertFalse(result.complete)
        } finally {
            http.close()
        }
    }
}
