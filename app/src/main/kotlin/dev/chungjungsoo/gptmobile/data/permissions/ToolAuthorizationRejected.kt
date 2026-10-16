package dev.chungjungsoo.gptmobile.data.permissions

/** Dispatch refusal is separate from schema failure, provider failure and exhausted budgets. */
class ToolAuthorizationRejected(val code: String, message: String) : IllegalStateException(message)
