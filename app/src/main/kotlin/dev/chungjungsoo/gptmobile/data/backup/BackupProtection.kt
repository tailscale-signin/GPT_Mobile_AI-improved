package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BackupProtection(val enabled: Boolean = false, @Transient val password: String = "") {
    override fun toString(): String = "BackupProtection(enabled=$enabled, password=<redacted>)"
}
