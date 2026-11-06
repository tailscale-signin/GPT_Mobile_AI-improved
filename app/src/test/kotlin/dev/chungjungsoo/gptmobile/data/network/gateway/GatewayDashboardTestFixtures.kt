package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun dashboardFixture(): JsonObject = Json.parseToJsonElement(
    """{
      "schema":"gptmobile.gateway.dashboard.v1","schemaVersion":1,"instanceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "scope":"admitted_identity","serviceScope":"this_installation","capturedAt":100,"uptime":80,
      "availability":{"usage":true,"devices":true,"activities":true,"history":true,"service":true,"openJobs":true},
      "usage":{"available":true,"scope":"this_installation","outputTokens":12345,"tokensPerSecond":18.2,"unmeasuredRounds":2,
        "days":[{"day":"2026-11-05","outputTokens":2345,"missingMeasurements":2}]},
      "service":{"activeWorkers":1,"maxWorkers":2,"draining":false},"openJobs":3,"requests":14,"errors":1,"receivedBytes":120,"sentBytes":450,
      "devices":[{"address":"192.168.1.10","agent":"GPT Mobile · Android","state":"active","lastSeen":99,"activeRequests":1,"requests":14}],
      "activities":[{"id":"activity-1","kind":"search","title":"Search repository","status":"completed","updated":99}],
      "jobs":[{"id":"job-1","kind":"chat","title":"Local model","status":"running","updated":98}],
      "engineering":[]
    }"""
) as JsonObject

internal fun readinessFixture(model: String, ready: Boolean = true, available: Boolean = ready): JsonObject = Json.parseToJsonElement(
    """{"schemaVersion":1,"instanceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ready":$ready,
        "capabilities":{"generationReady":$ready,"modelAvailable":$available,"selectedModelDigest":"${dev.chungjungsoo.gptmobile.data.research.researchHash(model)}"},
        "service":{"draining":false}}"""
) as JsonObject
