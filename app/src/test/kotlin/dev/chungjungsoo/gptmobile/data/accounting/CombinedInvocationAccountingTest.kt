package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetExceeded
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.agent.CombinedReservationStatus
import dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.RequestAdmissionReceipt
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedInvocationAccountingTest {
    @Test
    fun actualFirstReservationReplacesEstimateAndRetryReservesDailyBudgetAgain() = runBlocking {
        val fixture = fixture(dailyLimit = 60)
        fixture.dao.reserve(fixture.logical, Int.MAX_VALUE, fixture.spend)
        fixture.accounting.reserve("physical-one", receipt())
        assertEquals(35L, fixture.dao.dailyCost(0, "USD"))
        assertTrue(runCatching { fixture.accounting.reserve("physical-two", receipt()) }.exceptionOrNull() is SpendAllowanceReached)
        fixture.accounting.finish(fixture.logical.copy(status = "FAILED"), null)
        assertEquals(35L, fixture.dao.dailyCost(0, "USD"))
        assertEquals(CombinedReservationStatus.RELEASED, fixture.store.open(fixture.context).snapshot().reservations["physical-two"]?.status)
        assertEquals("NOT_DISPATCHED", fixture.dao.rows["physical-two"]?.status)
    }

    @Test
    fun aggregatedRetryUsageNeverRefundsUnseparatedPhysicalAttempts() = runBlocking {
        val fixture = fixture(dailyLimit = 100)
        fixture.dao.reserve(fixture.logical, Int.MAX_VALUE, fixture.spend)
        fixture.accounting.reserve("one", receipt())
        fixture.accounting.reserve("two", receipt())
        fixture.accounting.finish(fixture.logical.copy(status = "COMPLETED", observedInputTokens = 10, observedOutputTokens = 5, inputTokenSource = "provider_reported", outputTokenSource = "provider_reported"), null)
        assertEquals(70L, fixture.dao.dailyCost(0, "USD"))
        assertEquals(70L, fixture.store.open(fixture.context).snapshot().costMicros)
        assertEquals(2, fixture.dao.rows.size)
        assertTrue(fixture.dao.rows.values.all { it.observedInputTokens == null && it.inputTokenSource == "reserved_unknown" })
    }

    @Test
    fun oneMeasuredPhysicalAttemptRefundsOnlyItsKnownUnusedReservation() = runBlocking {
        val fixture = fixture(dailyLimit = 60)
        fixture.dao.reserve(fixture.logical, Int.MAX_VALUE, fixture.spend)
        fixture.accounting.reserve("one", receipt())
        fixture.accounting.finish(fixture.logical.copy(status = "COMPLETED", observedInputTokens = 12, observedOutputTokens = 3, inputTokenSource = "runtime_reported", outputTokenSource = "runtime_reported"), 1)
        assertEquals(15L, fixture.dao.dailyCost(0, "USD"))
        val snapshot = fixture.store.open(fixture.context).snapshot()
        assertEquals(15L, snapshot.costMicros)
        assertEquals(CombinedReservationStatus.SETTLED, snapshot.reservations["one"]?.status)
        assertEquals(1L, snapshot.reservations["one"]?.usage?.reasoningOutputTokens)
    }

    @Test
    fun confirmedPreDispatchFailureReleasesBudgetAndUnboundedTransportNeverStarts() = runBlocking {
        val fixture = fixture(dailyLimit = 60)
        fixture.dao.reserve(fixture.logical, Int.MAX_VALUE, fixture.spend)
        fixture.accounting.reserve("one", receipt())
        fixture.accounting.notDispatched("one")
        assertTrue(runCatching { fixture.accounting.reserve("unbounded", receipt().copy(outputTokens = null)) }.exceptionOrNull() is CombinedBudgetExceeded)
        fixture.accounting.finish(fixture.logical.copy(status = "FAILED"), null)
        assertEquals(0L, fixture.dao.dailyCost(0, "USD"))
        assertEquals(0L, fixture.store.open(fixture.context).snapshot().charged.generatedTokens)
        assertEquals("NOT_DISPATCHED", fixture.dao.rows[fixture.logical.id]?.status)
    }

    private fun fixture(dailyLimit: Long): Fixture {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a"), mapOf("a" to "model"), "Answer the question", acceptedAtMillis = 1)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root-${java.util.UUID.randomUUID()}", profileUid = "a", persistBudget = false)
        val store = CombinedTurnBudgetStore.inMemory()
        val dao = MemoryDao()
        val logical = ModelInvocation("logical", "run", context.rootBudgetId, "provider", "model", "contributor", 5, 20, costMicros = 25, currency = "USD")
        val spend = SpendBudgetSettings(perDayMicros = dailyLimit)
        val price = ModelPrice("model", 1_000_000, 1_000_000, "test quote", System.currentTimeMillis())
        return Fixture(context, store, dao, logical, spend, CombinedInvocationAccounting(context, store, price, dao, logical, Int.MAX_VALUE, spend))
    }

    private fun receipt() = RequestAdmissionReceipt("contributor", "test", RuntimeFingerprint("connection", "route", "credential", "model", "model", "runtime", "template", "allocation"), "grants", 10, 20, 5, 1000, CapacityProvenance.DECLARED, TokenMeasurementKind.SERVING_TOKENIZER, true)

    private data class Fixture(val context: CombinedExecutionContext, val store: CombinedTurnBudgetStore, val dao: MemoryDao, val logical: ModelInvocation, val spend: SpendBudgetSettings, val accounting: CombinedInvocationAccounting)

    private class MemoryDao : InvocationDao {
        val rows = linkedMapOf<String, ModelInvocation>()
        override suspend fun save(invocation: ModelInvocation) {
            rows[invocation.id] = invocation
        }
        override suspend fun byId(id: String): ModelInvocation? = rows[id]
        override suspend fun forChat(chatId: Int): List<ModelInvocation> = rows.values.toList()
        override fun observeForChat(chatId: Int): Flow<List<ModelInvocation>> = flowOf(rows.values.toList())
        override fun recent(): Flow<List<ModelInvocation>> = flowOf(rows.values.toList())
        override fun statistics(): Flow<List<ModelInvocation>> = recent()
        override suspend fun committedTokens(turnKey: String): Long = rows.values.filter { it.turnKey == turnKey }.sumOf { it.inputTokens.toLong() + it.outputTokens }
        override suspend fun recover() = Unit
        override suspend fun clear() {
            rows.clear()
        }
        override suspend fun turnCost(turnKey: String, currency: String): Long = rows.values.filter { it.turnKey == turnKey && it.currency == currency }.sumOf { it.costMicros ?: 0 }
        override suspend fun dailyCost(start: Long, currency: String): Long = rows.values.filter { it.startedAt >= start && it.currency == currency }.sumOf { it.costMicros ?: 0 }
        override suspend fun forRootBudget(turnKey: String): List<ModelInvocation> = rows.values.filter { it.turnKey == turnKey }
        override suspend fun rootExecutionPolicy(turnKey: String): String? = forRootBudget(turnKey).firstNotNullOfOrNull { it.executionPolicyJson }
    }
}
