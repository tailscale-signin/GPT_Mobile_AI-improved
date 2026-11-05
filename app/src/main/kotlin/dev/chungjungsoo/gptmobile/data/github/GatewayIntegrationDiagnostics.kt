package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A harmless durable transport probe, independent of GitHub inventories and repository writes. */
class GatewayIntegrationDiagnostics @Inject constructor(private val settings: SettingRepository, private val client: GatewayEngineeringClient) {
    suspend fun run(profileUid: String, requestKey: String, progress: suspend (String) -> Unit): String {
        val checks = mutableListOf<String>()
        try {
            val profile = settings.engineeringGateway(profileUid)
            val config = profile.engineeringConfig()
            progress("Checking the selected engineering gateway identity and protocol")
            val principal = client.principal(config, requireReady = false)
            checks += "Capability handshake and authenticated identity: passed."
            val request = buildJsonObject {
                put("request_id", GitHubEvidenceBook.digest(requestKey))
                put("kind", "transport_probe")
            }
            val created = client.diagnostic(config, "jobs", request)
            val id = created["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            check(id.matches(Regex("diag_[a-f0-9]{32}"))) { "Gateway returned an invalid diagnostic job ID." }
            checks += "Harmless job submission: $id."
            val first = client.diagnosticEvents(config, id, 0).take(1).toList()
            check(first.size == 1 && first.single()["sequence"] == JsonPrimitive(1)) { "Initial event receipt is missing." }
            checks += "Initial event delivery: passed."
            progress("Reconnecting to the saved gateway probe and checking its completion receipt")
            check(settings.engineeringGateway(profileUid) == profile && client.principal(config, requireReady = false) == principal) { "Gateway connection changed during testing." }
            val events = client.diagnosticEvents(config, id, 1).toList()
            check(events.size == 2 && events.last()["sequence"] == JsonPrimitive(3)) { "Event stream resume lost or duplicated an event." }
            val resumed = client.diagnostic(config, "jobs/$id?after=1&limit=10")
            check(resumed["id"] == created["id"] && resumed["status"] == JsonPrimitive("COMPLETED") && resumed["next_sequence"] == JsonPrimitive(3)) { "Resume or completion receipt is missing." }
            check((resumed["events"] as? JsonArray)?.size == 2) { "Resume duplicated or lost events." }
            val duplicate = client.diagnostic(config, "jobs", request)
            check(duplicate["id"] == created["id"]) { "Idempotent submission created a duplicate job." }
            val exhausted = client.diagnostic(config, "jobs/$id?after=3&limit=10")
            check((exhausted["events"] as? JsonArray)?.isEmpty() == true) { "Completed event cursor replayed old events." }
            checks += "Reconnect, cursor resume, completion and duplicate suppression: passed."
            return "Gateway transport integration: passed.\n\n" + checks.joinToString("\n") { "• $it" } +
                "\n\nThis probe verifies durable gateway transport. Model/tool execution, GitHub writes and phone background recovery require their own acceptance tests and were not exercised."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return "Gateway integration: incomplete.\n\n" + checks.joinToString("\n") { "• $it" } +
                "\n\nBlocker: ${failure.message.orEmpty().take(1000)}\nNo GitHub inventory or repository mutation was substituted for the gateway test."
        }
    }
}
