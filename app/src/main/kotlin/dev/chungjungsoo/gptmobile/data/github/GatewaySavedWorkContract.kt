package dev.chungjungsoo.gptmobile.data.github

enum class GatewayActionReadiness { NOT_VERIFIED, VERIFIED }

/** A generation probe or an advertised feature cannot grant any action surface. */
data class GatewayActionReadinessEvidence(
    val identity: GatewayActionReadiness = GatewayActionReadiness.NOT_VERIFIED,
    val conversations: GatewayActionReadiness = GatewayActionReadiness.NOT_VERIFIED,
    val jobs: GatewayActionReadiness = GatewayActionReadiness.NOT_VERIFIED,
    val engineering: GatewayActionReadiness = GatewayActionReadiness.NOT_VERIFIED,
    val graph: GatewayActionReadiness = GatewayActionReadiness.NOT_VERIFIED
)

/** Fresh evidence from bounded GETs of the same installation/account. It never authorizes a write. */
internal data class GatewaySavedWorkContract(
    val identity: GatewayScopeIdentity,
    val source: GatewayAdmissionSource,
    val matchesExpectedSource: Boolean?,
    val readiness: GatewayActionReadinessEvidence = GatewayActionReadinessEvidence(
        identity = GatewayActionReadiness.VERIFIED,
        conversations = GatewayActionReadiness.VERIFIED,
        jobs = GatewayActionReadiness.VERIFIED
    )
)
