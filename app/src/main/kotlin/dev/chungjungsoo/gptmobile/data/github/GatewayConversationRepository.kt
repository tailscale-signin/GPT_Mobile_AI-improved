package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit submission and read-only reconnection have separate entry points. */
@Singleton
class GatewayConversationRepository @Inject constructor(
    private val settings: SettingRepository,
    private val store: GatewayWorkStore,
    private val client: GatewayConversationClient,
    private val engineering: GatewayEngineeringClient,
    private val database: ChatDatabaseV2
) {
    private val mutation = Mutex()
    private val observation = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val jobs get() = store.jobs()
    fun jobs(gatewayUid: String, limit: Int) = store.jobsForGateway(gatewayUid, limit.coerceIn(50, 5000))
    val jobCount get() = store.dao.observeJobCount()
    val pendingCommands get() = store.dao.observeCommands()
    fun observeConversation(chatId: Int) = store.observeConversation(chatId)
    suspend fun ownsRequest(requestId: String): Boolean = withContext(NonCancellable + Dispatchers.IO) { store.dao.command(requestId) != null }

    internal suspend fun verifiedIdentity(config: ProviderRequestConfig): GatewayIdentity = client.identity(config)

    internal suspend fun mutationConfig(binding: GatewayEngineeringBinding): ProviderRequestConfig {
        val config = config(binding)
        val identity = client.identity(config)
        verifyBindingIdentity(binding, identity)
        check(config(binding) == config) { "The original gateway configuration changed before submission." }
        return config.withGatewayIdentity(identity)
    }

    suspend fun ownsConversation(chatId: Int): Boolean {
        store.importLegacy()
        return store.dao.conversations(chatId).isNotEmpty() || store.bindings(chatId).isNotEmpty()
    }

    /** A changed local selection must not turn retained gateway work into a new local run. */
    suspend fun conversationWriteBlocker(chatId: Int): String? {
        store.importLegacy()
        val conversations = store.dao.conversations(chatId)
        val bindings = store.bindings(chatId)
        if (conversations.isEmpty() && bindings.isEmpty()) return null
        val chat = store.dao.chat(chatId) ?: return "The original conversation is unavailable. Its gateway work remains retained."
        if (chat.isTemporary) return "Saved gateway work cannot continue in a temporary conversation."
        val profiles = settings.fetchPlatformV2s()
        val features = settings.getFeatureSettings()
        for (conversation in conversations) {
            val scope = store.dao.scope(conversation.scopeId) ?: return "The original gateway identity is unavailable. Saved history remains read-only."
            if (scope.connectionState == "IDENTITY_CHANGED" || listOf(scope.gatewayId, scope.principalId, scope.identityScope).any { it.isBlank() || it == "legacy-unverified" }) return "The original gateway or account identity is unverified. Restore its original connection before continuing."
            val gateway = engineeringGatewayProfile(profiles, scope.connectionUid) ?: return "The original gateway connection is unavailable. Saved history remains read-only."
            if (runCatching { selectedEndpoint(gateway.apiUrl) }.getOrNull() != scope.endpoint) return "The original gateway endpoint changed. Restore it or start a new conversation."
            val profile = profiles.singleOrNull { it.uid == conversation.profileUid && it.enabled }
                ?: return "The original model profile is unavailable. Saved history remains read-only."
            if (chat.activePlatform != listOf(profile.uid)) return "This gateway conversation requires its original model profile as the only active participant."
            val selected = profile.copy(model = store.dao.chatModel(chatId, profile.uid) ?: profile.model, reasoning = features.conversationReasoning[chatId] ?: profile.reasoning)
            if (selected.model != conversation.model || !GitHubAutonomousEngineeringPolicy.supportsSavedJobs(selected)) return "The original model or provider changed. Restore its saved selection or start a new conversation."
            if (conversation.repository.isNullOrBlank()) {
                // Ordinary jobs remain owned by the original Android coordinator.
                // A foreign transcript has no server follow-up contract or local owner.
                if (conversation.projectionOwner != "ANDROID_RUN") return "This saved gateway transcript supports reading and Stop. Start a new chat for a new request."
                if (runCatching { selectedEndpoint(selected.apiUrl) }.getOrNull() != scope.endpoint) return "The original model endpoint changed. Restore it before continuing this conversation."
                continue
            }
            val previous = bindings.lastOrNull { it.conversationId == conversation.conversationId || conversation.conversationId == "legacy_${GatewayWorkStore.digest(it.request.requestId).take(32)}" }
            val originalContext = conversation.originalContext?.let { runCatching { json.decodeFromString<GitHubConversationContext>(it) }.getOrNull() } ?: previous?.originalContext
                ?: return "The original repository context is unavailable. Saved history remains read-only."
            val currentContext = features.conversationGitHub[chatId]
            if (currentContext == null || !sameWorkspace(originalContext, currentContext)) return "The original repository, branch, or GitHub connection changed. Restore the original workspace before continuing."
            if (!GatewayOwnedTurnPolicy.configured(selected, currentContext, features) || features.githubProfileOverrides[selected.uid]?.engineeringGatewayProfileUid != scope.connectionUid) return "The original gateway selection or remote tool consent changed. Restore it before continuing."
            val blocker = selectionBlocker(selected, previous?.executionSelection, conversation.executionBinding)
            if (blocker != null) return blocker
        }
        // A durable outbox can predate its canonical conversation identity.
        if (conversations.isEmpty()) {
            for (binding in bindings) {
                val profile = profiles.singleOrNull { it.uid == binding.request.profileId && it.enabled }
                    ?: return "The original model profile is unavailable. The saved submission remains retained."
                val current = profile.copy(model = store.dao.chatModel(chatId, profile.uid) ?: profile.model, reasoning = features.conversationReasoning[chatId] ?: profile.reasoning)
                val gateway = engineeringGatewayProfile(profiles, features.githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid)
                if (gateway?.uid != binding.profileUid || runCatching { selectedEndpoint(requireNotNull(gateway).apiUrl) }.getOrNull() != binding.endpoint) return "The original gateway selection changed. Restore it before retrying the saved submission."
                if (!GatewayOwnedTurnPolicy.configured(current, binding.originalContext, features)) return "Remote GitHub tools are disabled for the original saved submission."
                selectionBlocker(current, binding.executionSelection, null)?.let { return it }
            }
        }
        return null
    }

    private suspend fun selectionBlocker(profile: PlatformV2, saved: GatewayExecutionSelection?, bindingJson: String?): String? {
        return try {
            val binding = bindingJson?.let { json.parseToJsonElement(it) as? JsonObject }
            val privacy = saved?.privacyScope ?: (binding?.get("privacy_scope") as? JsonPrimitive)?.content
                ?: return "The original execution identity is incomplete. Saved history remains read-only."
            val current = executionSelection(profile, profile.providerConnectionUid?.let { settings.getProviderConnection(it) }, privacy)
            if (saved != null) check(current == saved)
            if (binding != null) GatewayExecutionAdmission(binding, JsonObject(emptyMap())).verifySelection(current)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "The original model profile or provider connection changed. Restore its saved settings or start a new conversation."
        }
    }

    private fun sameWorkspace(original: GitHubConversationContext, current: GitHubConversationContext): Boolean =
        original.connectionUid == current.connectionUid && original.owner == current.owner && original.repo == current.repo && original.ref == current.ref && original.mainProfileUid == current.mainProfileUid

    suspend fun submitNormalTurn(chatId: Int, context: GitHubConversationContext, profile: PlatformV2, requestId: String, content: String, scope: String, userMessageId: Int, assistantMessageId: Int, base: String = context.ref, localRunId: String? = null, admissionAllowed: () -> Boolean = { true }): GatewayEngineeringBinding = mutation.withLock {
        require(scope in setOf("read", "plan", "implement") && content.isNotBlank() && content.length <= 16000 && requestId.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        require(context.mainProfileUid == profile.uid && userMessageId > 0 && assistantMessageId > 0)
        check(admissionAllowed()) { "Tool consent changed before gateway admission." }
        check(GatewayOwnedTurnPolicy.configured(profile, context, settings.getFeatureSettings())) { "Remote GitHub tools are disabled for this profile." }
        val consentProfile = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled }
        check(GatewayOwnedTurnPolicy.configured(consentProfile, context, settings.getFeatureSettings())) { "Remote GitHub tool consent changed before admission." }
        val originalStoredProfile = requireNotNull(consentProfile)
        val originalStoredConnection = originalStoredProfile.providerConnectionUid?.let { settings.getProviderConnection(it) }
        store.importLegacy()
        store.binding(requestId)?.let { retained ->
            check(retained.chatId == chatId && retained.request.objective == content && retained.request.scope == scope && retained.userMessageId == userMessageId && retained.assistantMessageId == assistantMessageId) { "This command identity belongs to another accepted message." }
            return@withLock reconcileOrSubmit(retained, explicitSubmission = true, admissionAllowed = admissionAllowed)
        }
        for (pending in store.bindings(chatId).filter { it.request.requestId != requestId && it.job == null }) {
            if (store.dao.command(pending.request.requestId)?.state == "REJECTED") continue
            val reconciled = reconcileOrSubmit(pending, explicitSubmission = false)
            check(reconciled.job != null && !reconciled.conversationId.isNullOrBlank()) { "The previous saved submission is still unconfirmed. Reconnect or explicitly retry that original command before sending another message." }
        }
        var existing = store.dao.conversations(chatId).firstOrNull { !it.conversationId.startsWith("legacy_") }
        var verifiedSnapshot: GatewayConversationSnapshot? = null
        val gateway = settings.engineeringGateway(profile.uid)
        val config = gateway.engineeringConfig()
        val identity = client.identity(config)
        check(engineering.principal(config) == identity.principalId) { "The gateway changed its authenticated identity during admission." }
        if (existing != null) {
            verifyConnection(requireNotNull(store.dao.scope(requireNotNull(existing).scopeId)), gateway, identity)
            val verified = store.verifiedScope(gateway.uid, gateway.apiUrl, identity)
            val snapshot = client.conversation(config, requireNotNull(existing).conversationId)
            existing = store.acceptSnapshot(verified, snapshot)
            verifiedSnapshot = snapshot
        }
        val connection = profile.providerConnectionUid?.let { settings.getProviderConnection(it) }
        val originalPrivacy = existing?.executionBinding?.let { json.parseToJsonElement(it) as? JsonObject }?.get("privacy_scope") as? JsonPrimitive
        val selected = executionSelection(profile, connection, originalPrivacy?.content ?: "${identity.principalId}/chat/$chatId/repository/${context.owner}/${context.repo}")
        val previous = store.bindings(chatId).lastOrNull { it.conversationId == existing?.conversationId && it.executionSelection != null }
        if (existing != null) {
            val originalScope = requireNotNull(store.dao.scope(existing.scopeId))
            verifyConnection(originalScope, gateway, identity)
            check(existing.profileUid == profile.uid && existing.model == profile.model && existing.repository == "${context.owner}/${context.repo}" && existing.branch == context.ref) { "Continue this conversation with its original profile and repository branch, or start a new conversation." }
            if (previous != null) check(previous.executionSelection == selected) { "The selected profile changed after this conversation was admitted. Restore the original profile or start a new conversation." }
        }
        val originalContext = if (existing == null) {
            context
        } else {
            val sourceSha = requireNotNull(existing.sourceSha) { "The original conversation source commit is unavailable. Read its saved conversation before starting new work." }
            val captured = existing.originalContext?.let { json.decodeFromString<GitHubConversationContext>(it) } ?: context.copy(commitSha = sourceSha)
            check(captured.commitSha == sourceSha && captured.ref == existing.branch && captured.mainProfileUid == existing.profileUid && "${captured.owner}/${captured.repo}" == existing.repository) { "The original source context conflicts with the retained conversation." }
            captured
        }
        val targetBranch = if (existing == null) originalContext.ref else requireNotNull(verifiedSnapshot?.latestJob?.workingBranch ?: verifiedSnapshot?.workingBranch ?: previous?.job?.branch ?: previous?.request?.branch) { "The original working branch is unavailable. Reconnect before submitting a follow-up." }
        val targetBase = if (existing == null) base else requireNotNull(verifiedSnapshot?.latestJob?.base ?: verifiedSnapshot?.base ?: previous?.job?.base ?: previous?.request?.base) { "The original base branch is unavailable. Reconnect before submitting a follow-up." }
        check(admissionAllowed()) { "Tool consent changed before gateway binding admission." }
        check(settings.engineeringGateway(profile.uid).engineeringConfig() == config) { "The gateway configuration changed before binding admission." }
        val admission = engineering.prepareBinding(config.withGatewayIdentity(identity), selected)
        existing?.executionBinding?.let { GatewayExecutionAdmission(json.parseToJsonElement(it) as JsonObject, admission.capabilities).verify(selected) }
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled } ?: error("The selected profile was disabled during admission.")
        check(GatewayOwnedTurnPolicy.configured(current, context, settings.getFeatureSettings())) { "Remote GitHub tool consent changed during admission." }
        check(executionSelection(current, current.providerConnectionUid?.let { settings.getProviderConnection(it) }, selected.privacyScope) == executionSelection(originalStoredProfile, originalStoredConnection, selected.privacyScope)) { "The selected profile changed during admission." }
        check(settings.engineeringGateway(profile.uid).let { it.uid == gateway.uid && selectedEndpoint(it.apiUrl) == selectedEndpoint(gateway.apiUrl) }) { "The selected gateway changed during admission." }
        val options = settings.getFeatureSettings().githubEngineeringSettings
        val preferences = settings.getFeatureSettings().githubProfileOverrides[profile.uid] ?: GitHubChatProfileOverride()
        val binding = GatewayEngineeringBinding(
            profileUid = gateway.uid,
            endpoint = selectedEndpoint(gateway.apiUrl),
            request = GatewayEngineeringRequest("${originalContext.owner}/${originalContext.repo}", targetBranch, targetBase, content, profile.model, options.durationHours, options.repairAttempts, chatId, requestId, options.acceptance, profile.uid, preferences.engineeringMemory, admission.bindingId, scope, existing?.profileName ?: profile.name, existing?.conversationId, originalContext.ref, originalContext.commitSha),
            principalId = identity.principalId,
            executionSelection = selected,
            executionAdmission = admission,
            gatewayId = identity.gatewayId,
            identityScope = identity.identityScope,
            conversationId = existing?.conversationId,
            originalContext = originalContext,
            userMessageId = userMessageId,
            assistantMessageId = assistantMessageId,
            localRunId = localRunId
        )
        store.verifiedScope(gateway.uid, gateway.apiUrl, identity)
        val payload = if (existing == null) json.encodeToString(binding.request) else followUpPayload(binding, existing.revision).toString()
        check(admissionAllowed()) { "Tool consent changed before saving this gateway submission." }
        store.prepare(binding, if (existing == null) "create" else "follow_up", payload)
        reconcileOrSubmit(binding, explicitSubmission = true, admissionAllowed = admissionAllowed)
    }

    /** Only called from an explicit Start/Send after the exact intent is durable. */
    internal suspend fun submitPrepared(binding: GatewayEngineeringBinding): GatewayEngineeringBinding = mutation.withLock {
        if (store.dao.command(binding.request.requestId) == null) store.prepare(binding)
        reconcileOrSubmit(binding, explicitSubmission = true)
    }

    /** A separate explicit action retries the original durable payload and command identity. */
    suspend fun retrySavedSubmission(requestId: String): GatewayEngineeringBinding = mutation.withLock {
        val binding = requireNotNull(store.binding(requestId)) { "The original saved command is unavailable." }
        requireNotNull(store.dao.command(requestId))
        reconcileOrSubmit(binding, explicitSubmission = true)
    }

    private suspend fun reconcileOrSubmit(binding: GatewayEngineeringBinding, explicitSubmission: Boolean, admissionAllowed: () -> Boolean = { true }): GatewayEngineeringBinding {
        val requestId = binding.request.requestId
        var admissionPostDispatched = false
        try {
            val config = config(binding)
            val identity = client.identity(config)
            verifyBindingIdentity(binding, identity)
            val scope = store.verifiedScope(binding.profileUid, binding.endpoint, identity)
            val receipt = client.command(config, requestId)
            if (receipt != null) return acceptReceipt(binding, identity, receipt)
            if (binding.job != null || !explicitSubmission) {
                if (binding.job == null) store.commandState(requestId, "UNKNOWN", "The gateway has not confirmed this saved request. Reconnect reads its original command; it does not create work.")
                return store.binding(requestId) ?: binding
            }
            val command = requireNotNull(store.dao.command(requestId))
            val currentProfile = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.request.profileId && it.enabled }
            val features = settings.getFeatureSettings()
            check(currentProfile != null && !currentProfile.disableAllTools && !currentProfile.disableRemoteTools && features.githubProfileOverrides[currentProfile.uid]?.githubTools == true) { "Remote GitHub tools are disabled; the saved request was not submitted." }
            check(settings.engineeringGateway(currentProfile.uid).let { it.uid == binding.profileUid && selectedEndpoint(it.apiUrl) == selectedEndpoint(binding.endpoint) }) { "The original gateway selection changed; the saved request was not submitted." }
            val selectedProfile = currentProfile.copy(model = store.dao.chatModel(binding.chatId, currentProfile.uid) ?: currentProfile.model, reasoning = features.conversationReasoning[binding.chatId] ?: currentProfile.reasoning)
            check(selectionBlocker(selectedProfile, binding.executionSelection, null) == null) { "The original execution selection changed; the saved request was not submitted." }
            store.commandState(requestId, "SUBMITTING")
            if (command.action == "follow_up") {
                val value = json.parseToJsonElement(command.payload) as JsonObject
                val expected = (value["expected_revision"] as JsonPrimitive).content.toLong()
                requireSavedSelection(binding)
                check(admissionAllowed()) { "Tool consent changed before gateway submission. The original command remains saved." }
                check(config(binding) == config) { "The original gateway configuration changed before submission." }
                admissionPostDispatched = true
                return acceptReceipt(binding, identity, client.followUp(config.withGatewayIdentity(identity), requireNotNull(binding.conversationId), requestId, expected, binding.request.objective, binding.request.scope))
            }
            val selection = requireNotNull(binding.executionSelection)
            requireNotNull(binding.executionAdmission).verify(selection)
            engineering.bindingCapabilities(config, selection, requireNotNull(binding.request.executionBindingId))
            requireSavedSelection(binding)
            check(admissionAllowed()) { "Tool consent changed before gateway submission. The original command remains saved." }
            check(config(binding) == config) { "The original gateway configuration changed before submission." }
            admissionPostDispatched = true
            val job = engineering.create(config.withGatewayIdentity(identity), binding.request)
            validateJob(binding, job)
            check(job.gatewayId == identity.gatewayId && job.principalId == identity.principalId && !job.conversationId.isNullOrBlank()) { "The gateway did not return a durable conversation admission identity." }
            val accepted = binding.copy(job = job, gatewayId = identity.gatewayId, identityScope = identity.identityScope, conversationId = job.conversationId)
            store.accept(accepted)
            store.dao.scope(scope.id)?.let { saved -> store.verifiedScope(saved.connectionUid, saved.endpoint, identity) }
            return accepted
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { store.commandState(requestId, "UNKNOWN", "Submission was interrupted. The same command must be reconciled before any new work.") }
            throw cancelled
        } catch (failure: Exception) {
            // A lost HTTP response cannot prove non-admission. Neither this path
            // nor reconnect creates a replacement command or local AgentRunner.
            val status = when (failure) {
                is GatewayContinuityFailure -> failure.status
                is GatewayEngineeringHttpFailure -> failure.status
                is GatewayExecutionRejected -> failure.httpStatus
                else -> null
            }
            store.commandState(requestId, if (admissionPostDispatched && binding.job == null && status in setOf(400, 401, 403, 409, 422)) "REJECTED" else "UNKNOWN", failure.message)
            return store.binding(requestId) ?: binding
        }
    }

    private suspend fun requireSavedSelection(binding: GatewayEngineeringBinding) {
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.request.profileId && it.enabled }
            ?: error("The original model profile is unavailable. The saved request was not submitted.")
        val features = settings.getFeatureSettings()
        check(!profile.disableAllTools && !profile.disableRemoteTools && features.githubProfileOverrides[profile.uid]?.githubTools == true) { "Remote GitHub tool consent changed before submission." }
        val selected = profile.copy(model = store.dao.chatModel(binding.chatId, profile.uid) ?: profile.model, reasoning = features.conversationReasoning[binding.chatId] ?: profile.reasoning)
        check(selectionBlocker(selected, binding.executionSelection, null) == null) { "The original execution selection changed before submission." }
        check(settings.engineeringGateway(profile.uid).let { it.uid == binding.profileUid && selectedEndpoint(it.apiUrl) == selectedEndpoint(binding.endpoint) }) { "The original gateway changed before submission." }
    }

    private suspend fun acceptReceipt(binding: GatewayEngineeringBinding, identity: GatewayIdentity, receipt: GatewayCommandReceipt): GatewayEngineeringBinding {
        check(receipt.commandId == binding.request.requestId && receipt.gatewayId == identity.gatewayId && receipt.principalId == identity.principalId && receipt.jobId == receipt.job.id && receipt.status == "accepted") { "The gateway command receipt does not match its durable intent." }
        check(binding.conversationId == null || binding.conversationId == receipt.conversationId) { "The gateway moved a command to another conversation." }
        validateJob(binding, receipt.job)
        return binding.copy(job = receipt.job, gatewayId = identity.gatewayId, identityScope = identity.identityScope, conversationId = receipt.conversationId).also { store.accept(it) }
    }

    private fun validateJob(binding: GatewayEngineeringBinding, job: GatewayEngineeringJob) {
        val source = job.executionBinding
        val derivedOwnedBranch = (binding.request.scope == "implement" || binding.conversationId != null) && binding.request.branch == binding.request.base && job.workingBranch == job.branch && job.branch.startsWith("codex/gateway-") && job.sourceBranch == binding.request.sourceBranch && job.sourceSha == binding.request.sourceSha
        check(job.id.matches(Regex("eng_[a-f0-9]{32}")) && job.repository == binding.request.repository && (job.branch == binding.request.branch || derivedOwnedBranch) && (binding.conversationId != null || job.chatId == binding.request.chatId)) { "The gateway job destination differs from the accepted request." }
        check(job.base == null || job.base == binding.request.base) { "The gateway changed the original base branch." }
        check(job.executionBindingId == binding.request.executionBindingId || binding.conversationId != null) { "The gateway changed the admitted execution binding." }
        val admission = requireNotNull(binding.executionAdmission)
        GatewayExecutionAdmission(requireNotNull(source), admission.capabilities).verify(requireNotNull(binding.executionSelection))
        check(source["binding_id"] == JsonPrimitive(job.executionBindingId)) { "The returned execution binding identity is inconsistent." }
    }

    suspend fun observeRequest(requestId: String): GatewayEngineeringBinding = observation.withLock {
        val binding = requireNotNull(store.binding(requestId))
        val retained = readBinding(binding)
        retained.conversationId?.let { id ->
            val conversation = store.dao.conversations(retained.chatId).firstOrNull { it.conversationId == id }
            if (conversation != null) reconnectSavedLocked(conversation)
        }
        store.binding(requestId) ?: retained
    }

    private suspend fun readBinding(binding: GatewayEngineeringBinding): GatewayEngineeringBinding {
        var retained = if (binding.job == null) reconcileOrSubmit(binding, explicitSubmission = false) else binding
        if (retained.job != null && (!requireNotNull(retained.job).terminal || retained.syncedRevision < requireNotNull(retained.job).revision)) {
            val config = config(retained)
            val identity = client.identity(config)
            verifyBindingIdentity(retained, identity)
            val snapshot = engineering.get(config, requireNotNull(retained.job).id, retained.syncedRevision)
            validateJob(retained, snapshot)
            check(snapshot.revision >= requireNotNull(retained.job).revision) { "The gateway returned an older job revision." }
            val cursor = snapshot.nextRevision ?: snapshot.revision
            check(cursor in retained.syncedRevision..snapshot.revision && (!snapshot.hasMoreUpdates || cursor > retained.syncedRevision)) { "The engineering update cursor did not advance." }
            retained = retained.copy(job = snapshot.copy(updates = emptyList()), updates = snapshot.updates, syncedRevision = cursor)
            store.saveBinding(retained)
        }
        return store.binding(binding.request.requestId) ?: retained
    }

    suspend fun refreshCatalog(gatewayProfileUid: String) = observation.withLock {
        store.importLegacy()
        val features = settings.getFeatureSettings()
        val allowed = features.githubProfileOverrides.values.mapNotNull { it.engineeringGatewayProfileUid }.toSet()
        for (profile in settings.fetchPlatformV2s().filter { it.uid == gatewayProfileUid && it.uid in allowed && GitHubAutonomousEngineeringPolicy.supportsSavedJobs(it) }) {
            var saved: GatewayScopeRecord? = store.dao.scopes().firstOrNull { it.connectionUid == profile.uid && it.endpoint == selectedEndpoint(profile.apiUrl) }
            try {
                val identity = client.identity(profile.engineeringConfig())
                var scope = store.verifiedScope(profile.uid, profile.apiUrl, identity)
                saved = scope
                var pages = 0
                do {
                    val page = client.catalog(profile.engineeringConfig(), scope.catalogCursor)
                    store.acceptCatalog(scope, page)
                    scope = requireNotNull(store.dao.scope(scope.id))
                    pages++
                } while (scope.catalogHasMore && pages < 4)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                saved?.let { store.connectionFailure(it, failure) }
            }
        }
    }

    suspend fun reconnectConversation(chatId: Int) = observation.withLock {
        store.importLegacy()
        // Pending admission has no conversation ID yet. Reconcile its original
        // command with GET before enumerating accepted conversations.
        for (binding in store.bindings(chatId).filter { it.job == null || it.conversationId != null }) readBinding(binding)
        for (conversation in store.dao.conversations(chatId).filterNot { it.conversationId.startsWith("legacy_") }) reconnectSavedLocked(conversation)
    }

    suspend fun reconnectSaved(conversation: GatewayConversationRecord) = observation.withLock { reconnectSavedLocked(conversation) }

    private suspend fun reconnectSavedLocked(conversation: GatewayConversationRecord) {
        val scope = requireNotNull(store.dao.scope(conversation.scopeId))
        store.projectRetainedMessages(conversation.id)
        try {
            val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == scope.connectionUid && it.enabled } ?: error("The original gateway connection is unavailable. Cached messages remain readable.")
            check(selectedEndpoint(profile.apiUrl) == scope.endpoint) { "The original gateway endpoint changed. Cached work cannot move to another endpoint." }
            val config = profile.engineeringConfig()
            val identity = client.identity(config)
            verifyConnection(scope, profile, identity)
            val currentScope = store.verifiedScope(profile.uid, profile.apiUrl, identity)
            val snapshot = client.conversation(config, conversation.conversationId)
            if (!store.acceptRetainedSnapshot(currentScope, snapshot, conversation)) return
            var pages = 0
            do {
                val cursor = store.dao.cursor(conversation.id)
                val page = client.messages(config, conversation.conversationId, cursor?.cursor)
                store.replay(currentScope, conversation.id, page)
                pages++
            } while (page.hasMore && pages < 4)
            store.projectRetainedMessages(conversation.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            store.connectionFailure(scope, failure)
            throw failure
        }
    }

    /** Opening a retained conversation creates only its local view, never server execution. */
    suspend fun openConversation(conversationKey: String, gitHubConnectionUid: String?): Int {
        var conversation = requireNotNull(store.dao.conversation(conversationKey))
        check(conversation.profileUid.isNotBlank()) { "This legacy conversation has no recorded profile. Open its retained transcript instead; no profile can be inferred." }
        val context = conversation.originalContext?.let { json.decodeFromString<GitHubConversationContext>(it) } ?: contextFor(conversation, gitHubConnectionUid)
        check(conversation.repository.isNullOrBlank() || context != null) { "The original repository source identity is incomplete. Read the saved conversation; its execution context cannot be replaced with today's selection." }
        val localId = database.withTransaction {
            val current = requireNotNull(store.dao.conversation(conversationKey))
            val id = current.localChatId?.takeIf { store.dao.chat(it) != null } ?: store.dao.insertChat(ChatRoomV2(title = current.title, enabledPlatform = listOf(current.profileUid), activePlatform = listOf(current.profileUid))).toInt().also { id ->
                store.dao.updateConversation(current.copy(localChatId = id, originalContext = context?.let { json.encodeToString(it) }))
            }
            if (current.model.isNotBlank()) {
                val selected = store.dao.chatModel(id, current.profileUid)
                check(selected == null || selected == current.model) { "This local chat now selects a different model. Read the saved conversation or restore the original model before continuing this gateway conversation." }
                if (selected == null) store.dao.putChatModel(ChatPlatformModelV2(id, current.profileUid, current.model))
            }
            id
        }
        val parameters = conversation.executionBinding?.let { json.parseToJsonElement(it) as? JsonObject }?.get("generation_parameters") as? JsonObject
        val reasoning = (parameters?.get("reasoning") as? JsonPrimitive)?.booleanOrNull
        if (context != null || reasoning != null) {
            settings.mutateFeatureSettings { latest ->
                val original = latest.conversationReasoning[localId]
                check(reasoning == null || original == null || original == reasoning) { "This local chat now selects different reasoning behavior. Read the saved conversation or restore the original setting before continuing." }
                latest.copy(
                    conversationGitHub = if (context == null) latest.conversationGitHub else latest.conversationGitHub + (localId to context),
                    conversationReasoning = if (reasoning == null) latest.conversationReasoning else latest.conversationReasoning + (localId to reasoning)
                )
            }
        }
        conversation = requireNotNull(store.dao.conversation(conversationKey))
        store.projectRetainedMessages(conversationKey)
        // Network failure leaves the local retained view available to navigation.
        try {
            reconnectSaved(conversation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
        val viewed = requireNotNull(store.dao.conversation(conversationKey))
        store.dao.markSeen(conversationKey, store.dao.cursor(conversationKey)?.sequence ?: 0, viewed.revision)
        return localId
    }

    suspend fun stopConversation(chatId: Int): GatewayStopOutcome {
        for (pending in store.bindings(chatId).filter { it.job == null }) reconcileOrSubmit(pending, explicitSubmission = false)
        val row = store.dao.observeJobsForChat(chatId).first().firstOrNull { it.schedulingState !in setOf("completed", "failed", "cancelled", "interrupted") }
            ?: return GatewayStopOutcome("UNAVAILABLE", "No confirmed active gateway job is available to stop. Reconnect to confirm any pending submission.")
        return stopJob(GatewaySavedJob(row, json.decodeFromString(row.payload), requireNotNull(store.dao.conversation(row.conversationKey)), requireNotNull(store.dao.scope(row.scopeId))))
    }

    suspend fun stopRequest(requestId: String): GatewayStopOutcome {
        var binding = store.binding(requestId) ?: return GatewayStopOutcome("UNAVAILABLE", "The saved gateway request is unavailable.")
        if (binding.job == null) binding = reconcileOrSubmit(binding, explicitSubmission = false)
        val jobId = binding.job?.id ?: return GatewayStopOutcome("UNAVAILABLE", "Submission is still unconfirmed. Reconnect reads the original command before Stop can target a job.")
        val record = requireNotNull(store.dao.binding(requestId))
        val row = store.dao.job(record.scopeId, "engineering", jobId) ?: return GatewayStopOutcome("UNAVAILABLE", "The retained job is unavailable.")
        val saved = GatewaySavedJob(row, json.decodeFromString(row.payload), requireNotNull(store.dao.conversation(row.conversationKey)), requireNotNull(store.dao.scope(row.scopeId)))
        if (saved.job.terminal) return GatewayStopOutcome(if (saved.job.schedulingState == "cancelled") "CONFIRMED" else "NOT_CANCELLED", "The gateway job is already ${saved.job.schedulingState}.")
        return stopJob(saved)
    }

    suspend fun stopJob(saved: GatewaySavedJob): GatewayStopOutcome = mutation.withLock {
        require("cancel" in saved.job.permittedActions || saved.job.kind == "engineering" && saved.row.engineeringPayload != null) { "The gateway has not advertised permission to stop this job." }
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == saved.connection.connectionUid && it.enabled } ?: error("The original gateway connection is unavailable.")
        val config = profile.engineeringConfig()
        val identity = client.identity(config)
        verifyConnection(saved.connection, profile, identity)
        if (saved.job.kind == "engineering") {
            val observed = engineering.get(config, saved.job.jobId, -1)
            check(observed.revision.toLong() == saved.job.revision) { "The job changed. Reconnect before confirming Stop." }
            requireUnchangedGateway(profile)
            val result = engineering.control(config.withGatewayIdentity(identity), observed, "cancel")
            check(result.id == saved.job.jobId)
            val state = result.schedulingState ?: if (result.state == "CANCELLED") "cancelled" else saved.job.schedulingState
            store.updateJob(saved.connection, saved.conversation, saved.job.copy(revision = result.revision.toLong(), phase = result.state, schedulingState = state, finishedAtMs = result.finishedAt?.let { (it * 1000).toLong() }, serverTimeMs = result.serverTimeMs ?: saved.job.serverTimeMs), result)
            val binding = store.bindings(saved.conversation.localChatId).lastOrNull { it.job?.id == result.id }
            if (binding != null) store.saveBinding(binding.copy(job = result))
            if (state == "cancelled") GatewayStopOutcome("CONFIRMED", "The gateway confirmed that this job stopped.") else GatewayStopOutcome("REQUESTED", "Stop requested. The gateway has not yet confirmed termination.")
        } else {
            requireUnchangedGateway(profile)
            val result = client.cancelOrdinary(config.withGatewayIdentity(identity), saved.job.jobId)
            check(result["job_id"] == JsonPrimitive(saved.job.jobId)) { "The cancellation receipt belongs to another job." }
            val confirmed = result["canceled"] == JsonPrimitive(true) || result["cancellationOutcome"] == JsonPrimitive("confirmed")
            val requested = result["cancellationRequested"] == JsonPrimitive(true)
            when {
                confirmed -> {
                    store.updateJob(saved.connection, saved.conversation, saved.job.copy(schedulingState = "cancelled", phase = "cancelled"))
                    GatewayStopOutcome("CONFIRMED", "The gateway confirmed that this job stopped.")
                }
                requested -> GatewayStopOutcome("REQUESTED", "Stop requested. The gateway has not yet confirmed termination.")
                else -> GatewayStopOutcome("NOT_CANCELLED", "The gateway did not cancel this job; reconnect to inspect its final state.")
            }
        }
    }

    private suspend fun config(binding: GatewayEngineeringBinding): ProviderRequestConfig {
        val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == binding.profileUid && it.enabled } ?: error("The original gateway profile is unavailable. Saved work remains retained.")
        check(selectedEndpoint(profile.apiUrl) == selectedEndpoint(binding.endpoint)) { "The original gateway endpoint changed; reconnect cannot substitute another server." }
        return profile.engineeringConfig()
    }

    private suspend fun requireUnchangedGateway(profile: PlatformV2) {
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid && it.enabled }
        check(current != null && GitHubAutonomousEngineeringPolicy.supportsSavedJobs(current) && current.compatibleType == profile.compatibleType && current.engineeringConfig() == profile.engineeringConfig()) { "The original gateway configuration changed. Refresh before confirming this action." }
    }

    private fun verifyBindingIdentity(binding: GatewayEngineeringBinding, identity: GatewayIdentity) {
        if (binding.principalId != identity.principalId || binding.gatewayId?.let { it != identity.gatewayId } == true || binding.identityScope?.let { it != identity.identityScope } == true) throw GatewayIdentityChanged()
    }

    private fun verifyConnection(scope: GatewayScopeRecord, profile: PlatformV2, identity: GatewayIdentity) {
        if (scope.connectionUid != profile.uid || scope.endpoint != selectedEndpoint(profile.apiUrl) || scope.gatewayId != identity.gatewayId || scope.principalId != identity.principalId || scope.identityScope != identity.identityScope) throw GatewayIdentityChanged()
    }

    private fun followUpPayload(binding: GatewayEngineeringBinding, revision: Long) = buildJsonObject {
        put("command_id", binding.request.requestId)
        put("expected_revision", revision)
        put("content", binding.request.objective)
        put("scope", binding.request.scope)
    }

    private fun contextFor(conversation: GatewayConversationRecord, connectionUid: String?): GitHubConversationContext? {
        if (connectionUid.isNullOrBlank() || conversation.profileUid.isBlank()) return null
        val repository = conversation.repository?.split('/')?.takeIf { it.size == 2 } ?: return null
        val source = conversation.sourceSha?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
        return GitHubConversationContext(connectionUid, repository[0], repository[1], conversation.branch ?: return null, source, conversation.profileUid)
    }
}
