package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.Serializable

@Serializable
data class ModelPrice(val model: String, val inputMicrosPerMillion: Long, val outputMicrosPerMillion: Long, val source: String, val checkedAt: Long) {
    fun cost(input: Int, output: Int): Long = cost(input.toLong(), output.toLong())

    fun cost(input: Long, output: Long): Long = dev.chungjungsoo.gptmobile.core.tokenCostMicros(input, output, inputMicrosPerMillion, outputMicrosPerMillion)
}

@Serializable
data class SpendBudgetSettings(val currency: String = "USD", val perTurnMicros: Long = 0, val perDayMicros: Long = 0, val prices: Map<String, ModelPrice> = emptyMap()) {
    val enforced get() = perTurnMicros > 0 || perDayMicros > 0
    fun validate() {
        require(currency.matches(Regex("[A-Z]{3}"))) { "Use a three-letter currency code." }
        java.util.Currency.getInstance(currency)
        require(perTurnMicros in 0..1_000_000_000_000 && perDayMicros in 0..1_000_000_000_000)
        prices.values.forEach {
            require(it.source.isNotBlank() && it.model.isNotBlank())
            it.cost(1, 1)
        }
    }
}
class SpendAllowanceReached(message: String) : IllegalStateException(message)
