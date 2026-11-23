package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.JsonObject

/** One frozen verifier authority. Model generation and accounting stay in the common executor. */
internal interface BenchmarkEngineeringTransport {
    val identityDigest: String

    /** Read/cancel authority can remain stable when the evaluator configuration changes. */
    val authorityDigest: String get() = identityDigest

    /** Logical verifier API requests, including preparation reads; distinct from model token usage. */
    val setupRequestCount: Int? get() = null

    /** A submission is admitted once; uncertain responses are reconciled by its original request ID. */
    suspend fun request(path: String, body: JsonObject? = null): JsonObject
}
