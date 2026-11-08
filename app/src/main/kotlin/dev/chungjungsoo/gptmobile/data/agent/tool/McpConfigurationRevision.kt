package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant
import dev.chungjungsoo.gptmobile.data.security.EndpointSecrets
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A durable comparison identity, built without reading or fingerprinting credential plaintext. */
internal suspend fun mcpConfigurationRevision(connection: ToolConnection, vault: SecretVault): String? {
    val authReference = connection.secretRef.takeUnless { connection.authType == ToolConnectionAuthType.NONE }
    if (connection.authType != ToolConnectionAuthType.NONE && authReference == null) return null
    // An unprotected query can contain credential plaintext. It has no durable record revision.
    if (connection.endpointUrl?.let { EndpointSecrets.masked(it) != it } == true) return null
    val references = listOfNotNull(authReference, EndpointSecrets.protectedReference(connection)).distinct().sorted()
    val revisions = mutableMapOf<String, String>()
    for (reference in references) {
        val revision = try {
            vault.recordRevision(reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (revision?.matches(Regex("[a-f0-9]{64}")) != true) return null
        revisions[reference] = revision
    }
    return ScopedToolGrant.canonicalHash(
        buildJsonObject {
            put("schema", "mcp-configuration-revision-v1")
            put("connection", connection.connectionUid)
            put("name", connection.name)
            put("alias", connection.alias)
            put("type", connection.type)
            put("endpoint", connection.endpointUrl?.let(EndpointSecrets::masked))
            put("authType", connection.authType)
            put("secretRef", authReference)
            put("oauthClientId", connection.oauthClientId)
            put("allowCleartext", connection.allowCleartext)
            put("toolPolicy", connection.toolPolicy)
            put("approvedReadTools", connection.approvedReadTools)
            put("createdAt", connection.createdAt)
            put("updatedAt", connection.updatedAt)
            put("marketplaceOrigin", connection.marketplaceOrigin)
            put("marketplaceProviderId", connection.marketplaceProviderId)
            put("marketplaceInstallationId", connection.marketplaceInstallationId)
            put("credentialRecords", buildJsonObject { revisions.forEach { (reference, revision) -> put(reference, revision) } })
        }
    )
}
