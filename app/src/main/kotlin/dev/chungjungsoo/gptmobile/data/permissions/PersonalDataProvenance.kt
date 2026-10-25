package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Labels carry authority and provenance only, never the personal values themselves. */
@Serializable
data class PersonalDataProvenance(
    val version: Int = 1,
    val sourceToolId: String,
    val sourceProfileUid: String,
    val sourceRef: String,
    val categories: Set<PersonalDataCategory>,
    val sourceRecipients: Set<String> = emptySet(),
    val retentionChatId: Int? = null,
    val rangeStart: Long? = null,
    val rangeEnd: Long? = null,
    val localOnly: Boolean = false
)

/** A bounded metadata projection also detects legacy tool results without reading their bodies. */
data class PersonalDataToolSource(val profileUid: String, val toolName: String, val connectionUid: String?)

/** Shared by a run and its children: summaries and derived arguments inherit source sensitivity. */
internal class PersonalDataContext(
    val chatId: Int?,
    private val grants: FreeModelToolConsentStore?,
    initial: List<PersonalDataProvenance> = emptyList()
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PersonalDataContext>
    private val labels = linkedMapOf<String, PersonalDataProvenance>()
    init {
        add(initial)
    }

    @Synchronized
    fun add(values: List<PersonalDataProvenance>) {
        values.forEach { value ->
            check(value.version == 1 && value.sourceToolId.isNotBlank() && value.categories.isNotEmpty()) { "PERSONAL_DATA_PROVENANCE_INVALID: Review the saved evidence before sharing." }
            val identity = researchHash("${value.sourceToolId}|${value.sourceProfileUid}|${value.categories.sortedBy { it.name }}|${value.localOnly}|${value.rangeStart}|${value.rangeEnd}|${value.sourceRecipients.sorted()}|${value.retentionChatId}")
            check(identity in labels || labels.size < 512) { "PERSONAL_DATA_SCOPE_TOO_LARGE: Start a new conversation with the selected evidence." }
            labels[identity] = value
        }
    }

    @Synchronized
    fun snapshot(): List<PersonalDataProvenance> = labels.values.toList()

    fun validateModel(profile: PlatformV2) {
        val retained = snapshot()
        if (retained.isEmpty()) return
        check(profile.isPrivateDestination() || retained.none { it.localOnly }) { "PERSONAL_DATA_LOCAL_ONLY: This saved context can only be used by a local destination." }
        val policy = ProviderPrivacyPolicy.forProfile(profile)
        if (policy == null) {
            check(profile.isPrivateDestination() || retained.none { it.sourceRecipients.isNotEmpty() && it.sourceProfileUid != profile.uid }) {
                "PERSONAL_DATA_RECIPIENT_REVIEW_REQUIRED: A source grant for another provider cannot authorize this writer. Keep the saved work with an approved recipient or explicitly provide the selected material in a new conversation."
            }
            return
        }
        check(policy.personalDataAllowed) { "PERSONAL_DATA_ROUTE_PROHIBITED: This provider cannot receive the saved personal context. Choose an eligible profile or start a public-only conversation." }
        retained.forEach { label ->
            val grant = grants?.grantFor(profile, label.sourceToolId, chatId)
            check(grant != null && grant.categories.containsAll(label.categories) && grant.recipients.containsAll(policy.recipients) && covers(grant, label)) {
                "PERSONAL_DATA_PERMISSION_REQUIRED: Review ${label.sourceToolId} for this profile and conversation before sharing saved or derived personal context."
            }
        }
    }

    /** A model recipient grant does not authorize a different search, map or plugin service. */
    fun validateExternalTool(profile: PlatformV2, toolId: String) {
        val retained = snapshot()
        if (retained.isEmpty()) return
        check(retained.none { it.localOnly }) { "PERSONAL_DATA_LOCAL_ONLY: External tool arguments are blocked for local-only context." }
        if (ProviderPrivacyPolicy.forProfile(profile) == null && retained.none { it.sourceRecipients.isNotEmpty() }) return
        // No inferred redaction can prove a model-generated query excludes all source facts.
        // Require an explicit service grant, or keep this request local and explain the gap.
        val grant = grants?.grantFor(profile, toolId, chatId)
        check(grant != null && "tool-service:$toolId" in grant.downstreamRecipients && retained.all { grant.categories.containsAll(it.categories) && covers(grant, it) }) {
            "PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED: This conversation contains personal context. Its derived query cannot be sent to $toolId without permission for that service. Use a new conversation with a nonpersonal query, or keep the work with the approved model."
        }
    }

    private fun covers(grant: PersonalDataGrant, label: PersonalDataProvenance): Boolean {
        if (PersonalDataCategory.HEALTH !in label.categories && grant.rangeStart == null) return true
        return grant.coversRange(label.rangeStart, label.rangeEnd, System.currentTimeMillis())
    }
}

/** Labels are preserved through caches, summaries and replay by AgentToolResult.copy. */
internal class ProvenanceGuardedTool(
    private val child: AgentTool,
    private val profile: PlatformV2,
    private val toolId: String,
    private val personal: Boolean,
    private val external: Boolean,
    private val grant: () -> PersonalDataGrant?
) : AgentTool by child,
    dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool,
    dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool {
    override val executionOwner = (child as? dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool)?.executionOwner ?: dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner.CLIENT
    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = ProvenanceGuardedTool(child.withChildBudget(bind), profile, toolId, personal, external, grant)
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()

    override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
        val context = currentCoroutineContext()[PersonalDataContext]
        if (external) context?.validateExternalTool(profile, toolId)
        val action = try {
            (child as? dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool)?.prepareExecution(callId, arguments) ?: suspend { child.execute(callId, arguments) }
        } catch (error: Exception) {
            // A connected source can place private values in an exception as well as a result.
            if (personal) context?.add(listOf(sourceLabel(callId, context.chatId)))
            throw error
        }
        return {
            if (external) context?.validateExternalTool(profile, toolId)
            val result = try {
                action()
            } catch (error: Exception) {
                if (personal) context?.add(listOf(sourceLabel(callId, context.chatId)))
                throw error
            }
            val own = if (personal && result.dispatched && result.personalData.none { it.sourceToolId == toolId && it.sourceProfileUid == profile.uid }) {
                listOf(sourceLabel(callId, context?.chatId))
            } else {
                emptyList()
            }
            context?.add(result.personalData + own)
            result.copy(personalData = (result.personalData + own + context?.snapshot().orEmpty()).distinct())
        }
    }

    private fun sourceLabel(callId: String, chatId: Int?): PersonalDataProvenance {
        val accepted = grant()
        return PersonalDataProvenance(
            sourceToolId = toolId,
            sourceProfileUid = profile.uid,
            sourceRef = researchHash(callId),
            categories = accepted?.categories ?: setOf(personalDataCategory(toolId)),
            sourceRecipients = accepted?.recipients ?: ProviderPrivacyPolicy.forProfile(profile)?.recipients.orEmpty(),
            retentionChatId = chatId,
            rangeStart = accepted?.rangeStart,
            rangeEnd = accepted?.rangeEnd
        )
    }
}
