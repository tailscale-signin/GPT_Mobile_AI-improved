package dev.chungjungsoo.gptmobile.data.rag

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The master choice survives vault loss or Keystore invalidation. Contains no fact text. */
@Singleton
class FactVaultPreferenceStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("fact_vault_preferences", Context.MODE_PRIVATE)
    fun enabled(): Boolean? = if (preferences.contains("enabled")) preferences.getBoolean("enabled", false) else null
    fun save(enabled: Boolean) {
        check(preferences.edit().putBoolean("enabled", enabled).commit())
    }
}
