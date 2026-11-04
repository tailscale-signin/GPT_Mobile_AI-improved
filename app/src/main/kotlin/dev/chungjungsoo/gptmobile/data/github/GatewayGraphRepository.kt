package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Graph requests use an explicitly selected private gateway and its existing GitHub account. */
@Singleton
class GatewayGraphRepository @Inject constructor(
    private val settings: SettingRepository,
    private val connections: ToolConnectionRepository,
    private val client: GatewayEngineeringClient
) {
    suspend fun execute(context: GitHubConversationContext, arguments: JsonObject): JsonObject {
        val connection = requireNotNull(connections.getConnection(context.connectionUid)) { "The GitHub connection was removed." }
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == context.mainProfileUid }
        require(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "Repository graphs require your private Llama or Ollama gateway profile." }
        val selected = requireNotNull(profile)
        val originalFeatures = settings.getFeatureSettings()
        val effectiveProfile = GitHubChatPolicy.platform(selected, originalFeatures, context)
        check(!effectiveProfile.disableAllTools && !effectiveProfile.disableRemoteTools) { "Tools are disabled for this profile." }
        val features = GitHubChatPolicy.features(originalFeatures, selected.uid, context)
        val override = features.githubProfileOverrides[selected.uid] ?: GitHubChatProfileOverride()
        check(
            features.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.GITHUB) &&
                features.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.connection(context.connectionUid))
        ) { "The GitHub plugin is disabled for this profile." }
        check(override.githubTools && override.engineeringMemory != GitHubEngineeringMemoryMode.SESSION_ONLY) { "Enable persistent engineering memory and GitHub tools to use a repository graph." }
        check(connection.type == "GITHUB") { "The selected connection is not GitHub." }
        val gateway = settings.engineeringGateway(selected.uid)
        val config = gateway.engineeringConfig()
        val principal = client.principal(config)
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
                client.principal(config) == principal
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
