package dev.chungjungsoo.gptmobile.data.research

interface ResearchJournal {
    fun scoped(authority: String, sharePublicEvidence: Boolean): ResearchJournal = this
    fun sourceId(identity: String, fallback: String): String = fallback
    fun claimWork(key: String, leaseMillis: Long): Boolean = true
    fun releaseWork(key: String) = Unit
    fun readerHealth(): dev.chungjungsoo.gptmobile.data.agent.ResearchReaderHealth? = null
    fun budget(totalLogicalCalls: Int, downloadLimitBytes: Int = 512 * 1024 * 1024): dev.chungjungsoo.gptmobile.data.agent.ResearchBudget? = null
    fun goalBudget(plan: dev.chungjungsoo.gptmobile.data.model.EffectiveResearchPlan): dev.chungjungsoo.gptmobile.data.agent.ResearchBudget =
        dev.chungjungsoo.gptmobile.data.agent.ResearchBudget(1, ledger = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger(plan.fingerprint, plan.limits))
    suspend fun load(): ResearchSnapshot?
    suspend fun load(task: String): ResearchSnapshot? = load()
    suspend fun retainBody(text: String): List<String> = emptyList()
    suspend fun save(snapshot: ResearchSnapshot)
    fun stopRequested(): Boolean
    suspend fun <T> withTaskLock(task: String, block: suspend () -> T): T = block()
}
