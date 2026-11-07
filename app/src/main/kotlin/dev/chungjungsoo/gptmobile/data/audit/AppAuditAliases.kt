package dev.chungjungsoo.gptmobile.data.audit

/** Aliases use frozen plan order; private target identities never become display text. */
fun appAuditTargetAliases(plan: List<AppAuditPlanEntry>): Map<String, String> =
    plan.mapNotNull { it.targetId }.distinct().mapIndexed { index, target -> target to "target-${index + 1}" }.toMap()
