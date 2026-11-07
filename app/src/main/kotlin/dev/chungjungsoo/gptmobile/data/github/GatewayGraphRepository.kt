package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Graph requests use an explicitly selected private gateway and its existing GitHub account. */
@Singleton
class GatewayGraphRepository @Inject constructor(
    private val settings: SettingRepository,
    private val connections: ToolConnectionRepository,
    private val client: GatewayEngineeringClient
) {
    /** Read-only admission for a plan that requires a graph; the same route/identity cache serves execution. */
    suspend fun readiness(context: GitHubConversationContext): String? = try {
        client.requireGraphReady(settings.engineeringGateway(context.mainProfileUid).engineeringConfig())
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        failure.message ?: "The selected gateway repository graph service is unavailable."
    } catch (_: Exception) {
        "The selected gateway repository graph readiness check could not complete. Check its connection and engineering service status."
    }

    suspend fun execute(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
        val connection = connections.getConnection(context.connectionUid)
            ?: throw GatewayGraphAdmissionException("PERMISSION_REVOKED", "The selected GitHub connection was removed. Reconnect or explicitly select an authorized connection.")
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == context.mainProfileUid }
        if (!GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "Repository graphs require the explicitly selected private Llama or Ollama gateway profile.")
        val selected = requireNotNull(profile)
        val originalFeatures = settings.getFeatureSettings()
        val effectiveProfile = GitHubChatPolicy.platform(selected, originalFeatures, context)
        if (effectiveProfile.disableAllTools || effectiveProfile.disableRemoteTools) throw GatewayGraphAdmissionException("PERMISSION_DENIED", "Tools are disabled for the selected profile.")
        val features = GitHubChatPolicy.features(originalFeatures, selected.uid, context)
        val override = features.githubProfileOverrides[selected.uid] ?: GitHubChatProfileOverride()
        if (!features.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.GITHUB) || !features.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.connection(context.connectionUid)) || !override.githubTools) {
            throw GatewayGraphAdmissionException("PERMISSION_DENIED", "The GitHub plugin or tools are disabled for the selected profile.")
        }
        if (override.engineeringMemory == GitHubEngineeringMemoryMode.SESSION_ONLY) throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "Enable persistent engineering memory for the selected profile to use its repository graph.")
        if (connection.type != "GITHUB") throw GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "The selected connection is not GitHub.")
        val gateway = settings.engineeringGateway(selected.uid)
        val config = gateway.engineeringConfig()
        val principal = client.requireGraphReady(config)
        val payload = boundRequest(context, arguments)
        val result = client.graph(config, payload)
        check(
            connections.getConnection(context.connectionUid) == connection &&
                settings.fetchPlatformV2s().singleOrNull { it.uid == selected.uid } == selected &&
                GitHubChatPolicy.features(settings.getFeatureSettings(), selected.uid, context).let { live ->
                    live.githubProfileOverrides[selected.uid] == features.githubProfileOverrides[selected.uid] &&
                        live.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.GITHUB) &&
                        live.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.connection(context.connectionUid))
                } &&
                settings.engineeringGateway(selected.uid) == gateway &&
                client.requireGraphReady(config) == principal
        ) { "The graph connection or permissions changed. Reopen the repository map." }
        return result
    }

    companion object {
        internal fun boundRequest(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
            require(arguments.toString().length <= 64_000) { "Graph request exceeds its inspection limit." }
            val pinned = mapOf("repository" to JsonPrimitive("${context.owner}/${context.repo}"), "branch" to JsonPrimitive(context.ref), "commit_sha" to JsonPrimitive(context.commitSha))
            pinned.forEach { (name, value) -> require(arguments[name] == null || arguments[name] == value) { "Graph request must use the selected repository revision." } }
            return JsonObject(arguments + pinned)
        }
    }
}
