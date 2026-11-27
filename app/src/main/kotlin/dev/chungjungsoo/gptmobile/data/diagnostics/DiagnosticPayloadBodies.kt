package dev.chungjungsoo.gptmobile.data.diagnostics

/** Immutable structured bodies recur inside changing event/observation envelopes. */
internal object DiagnosticPayloadBodies {
    val fields = setOf("acceptance_criteria", "acceptance_ledger", "capability_states", "catalog", "inventory", "requirements", "content", "result")

    // Redaction decisions may inspect sibling field names/values at this level.
    // Preserve that context when independently reusing a body's safe projection.
    val context = setOf("apiurl", "compatibletype", "endpointurl", "connectionuid", "jobid", "stage", "receiptid", "pointer", "value", "revision", "startline", "endline")
}
