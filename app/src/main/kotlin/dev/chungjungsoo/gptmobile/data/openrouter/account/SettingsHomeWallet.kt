package dev.chungjungsoo.gptmobile.data.openrouter.account

import kotlinx.serialization.json.JsonObject

/** Display data sourced exclusively from the authenticated account credits endpoint. */
@kotlinx.serialization.Serializable
data class SettingsHomeWalletSnapshot(
    val connectionUid: String,
    val connectionName: String,
    val connectionRevision: Long,
    val remainingUsd: String,
    val totalCreditsUsd: String,
    val totalUsageUsd: String,
    val observedAt: Long
) {
    fun isFresh(nowSeconds: Long): Boolean = nowSeconds - observedAt in 0..600

    companion object {
        fun fromCredits(
            connectionUid: String,
            connectionName: String,
            connectionRevision: Long,
            data: JsonObject,
            observedAt: Long
        ): SettingsHomeWalletSnapshot {
            val credits = data.money("total_credits")?.toBigDecimalOrNull()
                ?: throw OpenRouterAccountException(AccountFailure.MALFORMED)
            val usage = data.money("total_usage")?.toBigDecimalOrNull()
                ?: throw OpenRouterAccountException(AccountFailure.MALFORMED)
            return SettingsHomeWalletSnapshot(
                connectionUid,
                connectionName,
                connectionRevision,
                (credits - usage).stripTrailingZeros().toPlainString(),
                credits.stripTrailingZeros().toPlainString(),
                usage.stripTrailingZeros().toPlainString(),
                observedAt
            )
        }
    }
}

data class SettingsHomeWalletState(
    val snapshot: SettingsHomeWalletSnapshot? = null,
    val loading: Boolean = false,
    val failure: AccountFailure? = null,
    val accessSaved: Boolean = false,
    val associationValid: Boolean = false
)

suspend fun OpenRouterAccountClient.readHomeWallet(
    endpoint: String,
    credential: String,
    connectionUid: String,
    connectionName: String,
    connectionRevision: Long,
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
): SettingsHomeWalletSnapshot {
    val identity = key(endpoint, credential)
    if (!identity.isManagement) throw OpenRouterAccountException(AccountFailure.ROLE)
    if (identity.expired) throw OpenRouterAccountException(AccountFailure.EXPIRED)
    val data = objectResource(endpoint, credential, "credits")["data"] as? JsonObject
        ?: throw OpenRouterAccountException(AccountFailure.MALFORMED)
    return SettingsHomeWalletSnapshot.fromCredits(connectionUid, connectionName, connectionRevision, data, nowSeconds())
}
