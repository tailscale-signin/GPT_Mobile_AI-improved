package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** An exact policy response observation, never a permission or resource-absence verdict. */
internal class GitHubPolicyResponseObservation private constructor(
    private val path: String,
    private val expectedUrl: String,
    private val endpoint: String,
    private val kind: String,
    private val headerNames: List<String>
) {
    fun matchesRequest(requestedPath: String, status: Int, method: HttpMethod, expectedUrl: String): Boolean =
        requestedPath == path && status == 404 && method == HttpMethod.Get && expectedUrl == this.expectedUrl

    fun forEndpoint(requestedPath: String, status: Int): JsonObject? = if (requestedPath != path || status != 404) {
        null
    } else {
        buildJsonObject {
            put("schema", SCHEMA)
            put("endpoint", endpoint)
            put("http_status", 404)
            put("kind", kind)
            put("permission_header_names", JsonArray(headerNames.map(::JsonPrimitive)))
        }
    }

    companion object {
        private const val SCHEMA = "github.policy-response-observation.v1"
        private const val MAX_BODY_CHARS = 32_768
        private val protectionPath = Regex("/repos/[^/?#]+/[^/?#]+/branches/[^/?#]+/protection")
        private val rulesPath = Regex("/repos/[^/?#]+/[^/?#]+/rules/branches/[^/?#]+")
        private val endpoints = listOf("protection", "rules")
        private val headerVocabulary = linkedMapOf(
            "X-OAuth-Scopes" to "REPORTED_OAUTH_SCOPES",
            "X-Accepted-OAuth-Scopes" to "ACCEPTED_OAUTH_SCOPES",
            "X-Accepted-GitHub-Permissions" to "ACCEPTED_GITHUB_PERMISSIONS",
            "X-GitHub-SSO" to "SSO"
        )
        val fields = endpoints.flatMap { listOf("status_policy_${it}_404_kind", "status_policy_${it}_permission_header_names") }.toSet()

        fun capture(expectedUrl: String, response: HttpResponse, body: String): GitHubPolicyResponseObservation? {
            if (response.call.request.method != HttpMethod.Get || response.status.value != 404) return null
            val expected = try {
                Url(expectedUrl)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (expected.encodedQuery.isNotEmpty() || response.call.request.url != expected) return null
            // GitHubApiOrigins allows only the public root or the fixed Enterprise /api/v3 root.
            val path = expected.encodedPath.removePrefix("/api/v3")
            val endpoint = when {
                protectionPath.matches(path) -> "protection"
                rulesPath.matches(path) -> "rules"
                else -> return null
            }
            val message = if (body.length <= MAX_BODY_CHARS) {
                try {
                    ((Json.parseToJsonElement(body) as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                } catch (_: SerializationException) {
                    null
                }
            } else {
                null
            }
            val kind = when {
                endpoint == "protection" && message == "Branch not protected" -> "EXPLICIT_BRANCH_NOT_PROTECTED"
                message == "Not Found" -> "NOT_FOUND"
                else -> "OTHER_404"
            }
            // Inspect names only. Scope lists, permissions, SSO URLs and all other values stay private.
            val observedNames = response.headers.names()
            val headerNames = headerVocabulary.filterKeys { name -> observedNames.any { it.equals(name, ignoreCase = true) } }.values.toList()
            return GitHubPolicyResponseObservation(path, expectedUrl, endpoint, kind, headerNames)
        }

        fun policyFields(policy: JsonObject?): JsonObject = buildJsonObject {
            for (endpoint in endpoints) {
                val observation = policy?.get(endpoint) as? JsonObject ?: continue
                if (observation["available"] != JsonPrimitive(false) || observation["http_status"] != JsonPrimitive(404)) continue
                val source = observation["policy_response_observation"] as? JsonObject ?: continue
                if (source["schema"] != JsonPrimitive(SCHEMA) || source["endpoint"] != JsonPrimitive(endpoint) || source["http_status"] != JsonPrimitive(404)) continue
                val kind = validKind(endpoint, source["kind"] as? JsonPrimitive) ?: continue
                val names = validHeaderNames(source["permission_header_names"] as? JsonArray) ?: continue
                put("status_policy_${endpoint}_404_kind", kind)
                put("status_policy_${endpoint}_permission_header_names", names)
            }
        }

        fun coverageFields(coverage: JsonObject): JsonObject = buildJsonObject {
            for (endpoint in endpoints) {
                if (coverage["status_policy_${endpoint}_http_status"] != JsonPrimitive(404)) continue
                val kind = validKind(endpoint, coverage["status_policy_${endpoint}_404_kind"] as? JsonPrimitive) ?: continue
                val names = validHeaderNames(coverage["status_policy_${endpoint}_permission_header_names"] as? JsonArray) ?: continue
                put("status_policy_${endpoint}_404_kind", kind)
                put("status_policy_${endpoint}_permission_header_names", names)
            }
        }

        private fun validKind(endpoint: String, value: JsonPrimitive?): String? = value?.takeIf { it.isString }?.contentOrNull?.takeIf {
            it in setOf("NOT_FOUND", "OTHER_404") || endpoint == "protection" && it == "EXPLICIT_BRANCH_NOT_PROTECTED"
        }

        private fun validHeaderNames(value: JsonArray?): JsonArray? {
            if (value == null || value.size > headerVocabulary.size) return null
            val names = value.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: return null }
            if (names.distinct().size != names.size || names.any { it !in headerVocabulary.values }) return null
            return JsonArray(headerVocabulary.values.filter { it in names }.map(::JsonPrimitive))
        }
    }
}
