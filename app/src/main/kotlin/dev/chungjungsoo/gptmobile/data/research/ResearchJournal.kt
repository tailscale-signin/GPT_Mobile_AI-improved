package dev.chungjungsoo.gptmobile.data.research

interface ResearchJournal {
    fun scoped(authority: String, sharePublicEvidence: Boolean): ResearchJournal = this
    fun attributeSource(source: ResearchSource, discovered: Boolean = false, read: Boolean = false): ResearchSource = source
    fun sourceId(identity: String, fallback: String): String = fallback
    fun claimWork(key: String, leaseMillis: Long): Boolean = true
    fun releaseWork(key: String) = Unit
    fun claimPublicWork(key: String, leaseMillis: Long): Boolean = claimWork(key, leaseMillis)
    fun releasePublicWork(key: String) = releaseWork(key)
    fun readerHealth(): dev.chungjungsoo.gptmobile.data.agent.ResearchReaderHealth? = null
    fun budget(totalLogicalCalls: Int, downloadLimitBytes: Int = 512 * 1024 * 1024): dev.chungjungsoo.gptmobile.data.agent.ResearchBudget? = null
    fun goalBudget(plan: dev.chungjungsoo.gptmobile.data.model.EffectiveResearchPlan): dev.chungjungsoo.gptmobile.data.agent.ResearchBudget =
        dev.chungjungsoo.gptmobile.data.agent.ResearchBudget(1, ledger = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger(plan.fingerprint, plan.limits))
    suspend fun load(): ResearchSnapshot?
    suspend fun load(task: String): ResearchSnapshot? = load()
    suspend fun featurePolicy(): dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings = dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings()
    suspend fun archiveLeads(task: String): List<ResearchSource> = emptyList()
    suspend fun retainBody(text: String): List<String> = emptyList()
    suspend fun retainDeliveryReceipt(receipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt): Boolean = false
    suspend fun retainPartial(stage: String, profileUid: String, text: String): String? = null
    suspend fun bindTask(snapshot: ResearchSnapshot): ResearchSnapshot = snapshot
    suspend fun requiresEvidence(): Boolean? = null

    /** Accepted task planning only; this never grants access to another source or evidence scope. */
    suspend fun publicQuestionPlan(task: String, value: ResearchPlanContract? = null): ResearchPlanContract? = null
    suspend fun save(snapshot: ResearchSnapshot)
    fun cancellationOrigin(): String = "UNKNOWN"
    fun stopRequested(): Boolean
    suspend fun <T> withTaskLock(task: String, block: suspend () -> T): T = block()
}
