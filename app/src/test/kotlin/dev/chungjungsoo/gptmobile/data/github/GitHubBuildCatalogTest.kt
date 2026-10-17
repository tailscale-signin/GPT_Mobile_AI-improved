package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBuildCatalogTest {
    private val commit = "a".repeat(40)
    private val apkDigest = "sha256:${"d".repeat(64)}"
    private val archiveDigest = "sha256:${"e".repeat(64)}"

    @Test
    fun `release catalog shows published APK assets newest first and uses trusted identity`() = runTest {
        val payload = buildJsonArray {
            add(release("v1", "2026-10-15T10:00:00Z", listOf(asset(1, "app-arm64-v8a-release.apk")), target = "main"))
            add(release("draft", "2026-10-17T12:00:00Z", listOf(asset(2, "draft.apk")), draft = true))
            add(
                release(
                    "v2/pre",
                    "2026-10-17T10:00:00Z",
                    listOf(
                        asset(3, "app-universal-release.APK", digest = apkDigest),
                        asset(4, "app-release.apk.idsig"),
                        asset(5, "app-release.aab"),
                        asset(6, "uploading.apk", state = "starter"),
                        asset(-1, "invalid.apk")
                    ),
                    prerelease = true
                )
            )
        }
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("https", request.url.protocol.name)
                assertEquals("api.github.com", request.url.host)
                assertEquals("/repos/owner/repo/releases", request.url.encodedPath)
                assertEquals("30", request.url.parameters["per_page"])
                assertEquals("1", request.url.parameters["page"])
                assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
                assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
                respond(payload.toString())
            }
        )
        try {
            val page = GitHubBuildCatalog("private-token", http).releases("owner", "repo")
            assertEquals(listOf(3L, 1L), page.builds.map { it.assetId })
            val build = page.builds.first()
            assertEquals(GitHubBuildSource.RELEASE, build.source)
            assertEquals("release:owner/repo:3", build.id)
            assertEquals("v2/pre", build.releaseTag)
            assertEquals(commit, build.commitSha)
            assertEquals(apkDigest, build.digest)
            assertEquals(null, page.builds.last().commitSha)
            assertEquals(12345L, build.sizeBytes)
            assertTrue(build.prerelease)
            assertEquals("https://github.com/owner/repo/releases/tag/v2%2Fpre", build.detailsUrl)
            assertFalse(page.hasMore)
        } finally {
            http.close()
        }
    }

    @Test
    fun `release pagination remains available when this page has no APK and never follows returned URLs`() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("2", request.url.parameters["page"])
                assertEquals(null, request.headers[HttpHeaders.Authorization])
                respond(
                    JsonArray(listOf(release("v1", "2026-10-17T10:00:00Z", listOf(asset(1, "app-release.aab"))))).toString(),
                    headers = headersOf(HttpHeaders.Link, "<https://untrusted.example/collect>; rel=\"next\"")
                )
            }
        )
        try {
            val page = GitHubBuildCatalog("", http).releases("owner", "repo", page = 2)
            assertTrue(page.builds.isEmpty())
            assertTrue(page.hasMore)
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test
    fun `workflow builds keep only successful selected branch artifacts and retain expiration`() = runTest {
        val branch = "feature/apk+build"
        val requestedRuns = mutableListOf<Long>()
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("api.github.com", request.url.host)
                assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
                when (request.url.encodedPath) {
                    "/repos/owner/repo/actions/runs" -> {
                        assertEquals(branch, request.url.parameters["branch"])
                        assertEquals("success", request.url.parameters["status"])
                        assertEquals("10", request.url.parameters["per_page"])
                        respond(
                            runs(
                                listOf(
                                    run(101, branch),
                                    run(102, "main"),
                                    run(103, branch, conclusion = "failure"),
                                    run(104, branch, status = "in_progress")
                                ),
                                total = 25
                            )
                        )
                    }
                    "/repos/owner/repo/actions/runs/101/artifacts" -> {
                        requestedRuns += 101L
                        respond(
                            artifacts(
                                listOf(
                                    artifact(10, "app-debug"),
                                    artifact(11, "diagnostic-audit-debug-apk", expired = true),
                                    artifact(12, "signed-release-$commit", digest = archiveDigest),
                                    artifact(13, "jacoco-coverage-report"),
                                    artifact(14, "app-release.aab"),
                                    artifact(15, "app-debug-symbols"),
                                    artifact(16, "app-release.apk.idsig"),
                                    artifact(17, "apk-size-report")
                                )
                            )
                        )
                    }
                    else -> error("Unexpected artifact request for an unrelated or unsuccessful run")
                }
            }
        )
        try {
            val page = GitHubBuildCatalog("private-token", http).workflowBuilds("owner", "repo", branch)
            assertEquals(listOf(101L), requestedRuns)
            assertEquals(setOf(10L, 11L, 12L), page.builds.map { it.assetId }.toSet())
            assertTrue(page.hasMore)
            val debug = page.builds.first { it.assetId == 10L }
            assertEquals(GitHubBuildSource.WORKFLOW, debug.source)
            assertEquals("app-debug.zip", debug.fileName)
            assertEquals(branch, debug.branch)
            assertEquals(commit, debug.commitSha)
            assertEquals(101L, debug.runId)
            assertEquals("https://github.com/owner/repo/actions/runs/101", debug.detailsUrl)
            assertTrue(page.builds.first { it.assetId == 11L }.expired)
            assertEquals(archiveDigest, page.builds.first { it.assetId == 12L }.digest)
        } finally {
            http.close()
        }
    }

    @Test
    fun `workflow artifacts follow bounded page numbers without losing later APKs`() = runTest {
        val artifactPages = mutableListOf<String?>()
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/artifacts")) {
                    artifactPages += request.url.parameters["page"]
                    assertEquals("100", request.url.parameters["per_page"])
                    if (request.url.parameters["page"] == "1") {
                        respond(artifacts(listOf(artifact(1, "coverage-report")), total = 101))
                    } else {
                        respond(artifacts(listOf(artifact(2, "app-debug.zip")), total = 101))
                    }
                } else {
                    respond(runs(listOf(run(101, "main"))))
                }
            }
        )
        try {
            val page = GitHubBuildCatalog("token", http).workflowBuilds("owner", "repo", "main")
            assertEquals(listOf("1", "2"), artifactPages)
            assertEquals(listOf(2L), page.builds.map { it.assetId })
            assertEquals("app-debug.zip", page.builds.single().fileName)
            assertFalse(page.hasMore)
        } finally {
            http.close()
        }
    }

    @Test
    fun `artifact enumeration stops at its bound with an explicit incomplete result error`() = runTest {
        var artifactRequests = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/artifacts")) {
                    artifactRequests++
                    respond(artifacts(listOf(artifact(artifactRequests.toLong(), "app-debug")), total = 1000))
                } else {
                    respond(runs(listOf(run(101, "main"))))
                }
            }
        )
        try {
            val error = runCatching { GitHubBuildCatalog("token", http).workflowBuilds("owner", "repo", "main") }.exceptionOrNull()
            assertTrue(error?.message?.contains("too many artifacts") == true)
            assertEquals(3, artifactRequests)
        } finally {
            http.close()
        }
    }

    @Test
    fun `empty successful run page with more history remains pageable`() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("2", request.url.parameters["page"])
                respond(runs(listOf(run(1, "another-branch")), total = 21))
            }
        )
        try {
            val page = GitHubBuildCatalog("token", http).workflowBuilds("owner", "repo", "main", page = 2)
            assertTrue(page.builds.isEmpty())
            assertTrue(page.hasMore)
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test
    fun `HTTP errors give recovery guidance without leaking token or response bodies`() = runTest {
        val http = HttpClient(MockEngine { respond("private-token and private response data", HttpStatusCode.Forbidden) })
        try {
            val error = runCatching { GitHubBuildCatalog("private-token", http).releases("owner", "repo") }.exceptionOrNull()
            assertTrue(error?.message?.contains("denied build access") == true)
            assertFalse(error?.message?.contains("private-token") == true)
            assertFalse(error?.message?.contains("private response") == true)
        } finally {
            http.close()
        }
    }

    @Test
    fun `rate limited 403 is not diagnosed as a permission failure`() = runTest {
        val http = HttpClient(
            MockEngine {
                respond("internal body", HttpStatusCode.Forbidden, headersOf("Retry-After", "1"))
            }
        )
        try {
            val error = runCatching { GitHubBuildCatalog("token", http).releases("owner", "repo") }.exceptionOrNull()
            assertTrue(error?.message?.contains("limiting requests") == true)
            assertFalse(error?.message?.contains("permission") == true)
        } finally {
            http.close()
        }
    }

    @Test
    fun `cancellation remains cancellation`() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("cancelled by user") })
        try {
            val error = runCatching { GitHubBuildCatalog("token", http).releases("owner", "repo") }.exceptionOrNull()
            assertTrue(error is CancellationException)
        } finally {
            http.close()
        }
    }

    @Test
    fun `invalid repository branch and pages never reach the network`() = runTest {
        val http = HttpClient(MockEngine { error("Invalid input reached GitHub") })
        try {
            val catalog = GitHubBuildCatalog("token", http)
            assertTrue(runCatching { catalog.releases("owner/other", "repo") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { catalog.releases("..", "repo") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { catalog.releases("owner", ".") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { catalog.releases("owner", "repo", 0) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { catalog.workflowBuilds("owner", "repo", "") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { catalog.workflowBuilds("owner", "repo", "main", 101) }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            http.close()
        }
    }

    @Test
    fun `unbounded metadata bodies are rejected while streaming`() = runTest {
        val http = HttpClient(MockEngine { respond(" ".repeat(4 * 1024 * 1024 + 1)) })
        try {
            val error = runCatching { GitHubBuildCatalog("token", http).releases("owner", "repo") }.exceptionOrNull()
            assertTrue(error?.message?.contains("too large") == true)
        } finally {
            http.close()
        }
    }

    private fun asset(id: Long, name: String, state: String = "uploaded", digest: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("state", state)
        put("size", 12345)
        digest?.let { put("digest", it) }
        put("browser_download_url", "https://untrusted.example/download")
    }

    private fun release(tag: String, publishedAt: String, assets: List<JsonObject>, draft: Boolean = false, prerelease: Boolean = false, target: String = commit): JsonObject = buildJsonObject {
        put("tag_name", tag)
        put("name", "Release $tag")
        put("published_at", publishedAt)
        put("draft", draft)
        put("prerelease", prerelease)
        put("target_commitish", target)
        put("assets", JsonArray(assets))
        put("html_url", "https://untrusted.example/release")
    }

    private fun run(id: Long, branch: String, status: String = "completed", conclusion: String = "success"): JsonObject = buildJsonObject {
        put("id", id)
        put("name", "PR Validation")
        put("display_title", "Build #$id")
        put("status", status)
        put("conclusion", conclusion)
        put("head_branch", branch)
        put("head_sha", commit)
        put("created_at", "2026-10-17T10:00:00Z")
        put("html_url", "https://untrusted.example/run")
    }

    private fun artifact(id: Long, name: String, expired: Boolean = false, digest: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("size_in_bytes", 23456)
        put("created_at", "2026-10-17T10:00:00Z")
        put("expired", expired)
        digest?.let { put("digest", it) }
    }

    private fun runs(runs: List<JsonObject>, total: Int = runs.size): String = buildJsonObject {
        put("total_count", total)
        put("workflow_runs", JsonArray(runs))
    }.toString()

    private fun artifacts(artifacts: List<JsonObject>, total: Int = artifacts.size): String = buildJsonObject {
        put("total_count", total)
        put("artifacts", JsonArray(artifacts))
    }.toString()
}
