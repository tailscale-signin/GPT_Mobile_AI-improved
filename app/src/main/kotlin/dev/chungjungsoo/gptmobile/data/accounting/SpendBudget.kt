package dev.chungjungsoo.gptmobile.data.accounting

import java.math.BigInteger
import kotlinx.serialization.Serializable

@Serializable
data class ModelPrice(val model: String, val inputMicrosPerMillion: Long, val outputMicrosPerMillion: Long, val source: String, val checkedAt: Long) {
    fun cost(input: Int, output: Int): Long {
        require(input >= 0 && output >= 0 && inputMicrosPerMillion >= 0 && outputMicrosPerMillion >= 0)
        val numerator = BigInteger.valueOf(input.toLong()).multiply(BigInteger.valueOf(inputMicrosPerMillion)) + BigInteger.valueOf(output.toLong()).multiply(BigInteger.valueOf(outputMicrosPerMillion))
        return numerator.add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000)).longValueExact()
    }
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
