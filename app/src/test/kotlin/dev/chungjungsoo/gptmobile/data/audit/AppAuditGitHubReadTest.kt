package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditGitHubReadTest {
    private val repository = GitHubRepositoryContext("audit", "fixture", "main")

    @Test fun allSixProbesExerciseTheRealClientWithOnlyScopedFreshGetRequests() = runTest {
        var markers = 0
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(requests + 1, markers)
                requests++
                val body = when (request.url.encodedPath) {
                    "/user" -> """{"id":7,"login":"private-login"}"""
                    "/repos/audit/fixture" -> """{"id":8,"full_name":"audit/fixture"}"""
                    "/repos/audit/fixture/commits/main" -> """{"sha":"${"a".repeat(40)}","files":[]}"""
                    "/repos/audit/fixture/pulls", "/repos/audit/fixture/releases" -> "[]"
                    "/repos/audit/fixture/commits/main/check-runs" -> """{"total_count":0,"check_runs":[]}"""
                    else -> error("Unexpected GitHub read")
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val client = GitHubWorkspaceClient("fixture-token", http, conditionalReads = false, persistentState = false)
            for (check in AppAuditGitHubCatalog.live) {
                val result = appAuditGitHubRead(check, repository, "7", client) { markers++ }
                assertEquals(check.id, AppAuditOutcome.PASS, result.outcome)
                assertEquals(AppAuditObservationSource.LIVE, result.observationSource)
                assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
                assertEquals(200, result.httpStatus)
                assertFalse(result.toString().contains("private-login"))
                assertFalse(result.toString().contains("fixture-token"))
            }
            assertEquals(6, requests)
        } finally {
            http.close()
        }
    }

    @Test fun authorizationFailureAndTransportFailureHaveDifferentOutcomes() = runTest {
        for (status in listOf(401, 403, 404, 429)) {
            val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json")) })
            try {
                val result = readAccount(GitHubWorkspaceClient("fixture-$status", http, persistentState = false))
                assertEquals(if (status == 404) AppAuditOutcome.UNAVAILABLE else AppAuditOutcome.BLOCKED, result.outcome)
                assertEquals(status, result.httpStatus)
                assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
            } finally {
                http.close()
            }
        }
        val http = HttpClient(MockEngine { throw IOException("private transport detail") })
        try {
            val result = readAccount(GitHubWorkspaceClient("fixture-transport", http, persistentState = false))
            assertEquals(AppAuditOutcome.UNAVAILABLE, result.outcome)
            assertEquals("GITHUB_AUDIT_TRANSPORT_UNAVAILABLE", result.reasonCode)
            assertEquals(AppAuditDispatchEvidence.ATTEMPTED, result.dispatchEvidence)
            assertFalse(result.toString().contains("private transport detail"))
        } finally {
            http.close()
        }
    }

    @Test fun physicalRequestCapStopsBeforeTheFourthHttpCallAndIsNotRetried() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("{}", if (requests == 2) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NotModified, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val result = readAccount(GitHubWorkspaceClient("fixture-cap", http, conditionalReads = false, persistentState = false))
            assertEquals(3, requests)
            assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
            assertEquals("GITHUB_AUDIT_REQUEST_LIMIT", result.reasonCode)
        } finally {
            http.close()
        }
    }

    @Test fun wrongAccountAndMalformedSuccessCannotPass() = runTest {
        for ((body, expected) in listOf("""{"id":8}""" to AppAuditOutcome.BLOCKED, "{}" to AppAuditOutcome.FAIL)) {
            val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
            try {
                val result = readAccount(GitHubWorkspaceClient("fixture-response", http, persistentState = false))
                assertEquals(expected, result.outcome)
                assertEquals(200, result.httpStatus)
            } finally {
                http.close()
            }
        }
    }

    @Test fun missingRemoteInventoriesCannotBecomeSuccessfulEmptyInventories() = runTest {
        val sha = "a".repeat(40)
        val responses = listOf(
            Triple("get_commit_checks", "{}", AppAuditOutcome.FAIL),
            Triple("get_commit_checks", """{"check_runs":[]}""", AppAuditOutcome.FAIL),
            Triple("get_commit_checks", """{"total_count":-1,"check_runs":[]}""", AppAuditOutcome.FAIL),
            Triple("get_commit_checks", """{"total_count":0,"check_runs":[]}""", AppAuditOutcome.PASS),
            Triple("get_commit_detail", """{"sha":"$sha"}""", AppAuditOutcome.FAIL),
            Triple("get_commit_detail", """{"sha":"$sha","files":[]}""", AppAuditOutcome.PASS)
        )
        for ((action, body, expected) in responses) {
            var requests = 0
            val http = HttpClient(
                MockEngine {
                    requests++
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
            try {
                val check = AppAuditGitHubCatalog.live.single { it.action == action }
                val result = appAuditGitHubRead(check, repository, "7", GitHubWorkspaceClient("fixture-inventory", http, persistentState = false)) {}
                assertEquals(action, expected, result.outcome)
                assertEquals(200, result.httpStatus)
                assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
                assertEquals(1, requests)
            } finally {
                http.close()
            }
        }
    }

    @Test fun ordinaryClientCallsKeepTheirExistingInventoryNormalization() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            val client = GitHubWorkspaceClient("fixture-ordinary", http, persistentState = false)
            val arguments = buildJsonObject {
                put("owner", repository.owner)
                put("repo", repository.repo)
                put("ref", repository.ref)
            }
            for ((action, field) in listOf("get_commit_checks" to "check_runs", "get_commit_detail" to "files")) {
                val result = client.execute(action, arguments) as JsonObject
                assertEquals(JsonArray(emptyList()), result[field])
            }
        } finally {
            http.close()
        }
    }

    @Test fun cancellationRemainsCancellationWithoutAnAuditSuccess() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("fixture stop") })
        try {
            val failure = runCatching { readAccount(GitHubWorkspaceClient("fixture-cancel", http, persistentState = false)) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        } finally {
            http.close()
        }
    }

    private suspend fun readAccount(client: GitHubWorkspaceClient) = appAuditGitHubRead(AppAuditGitHubCatalog.live.first(), repository, "7", client) {}
}
