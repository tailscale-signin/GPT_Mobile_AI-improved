package dev.chungjungsoo.gptmobile.data.permissions

/** Dispatch refusal is separate from schema failure, provider failure and exhausted budgets. */
open class ToolAuthorizationRejected(val code: String, message: String) : IllegalStateException(message)

/** State of the earlier approval, independent of the refusal of this new invocation. */
internal class DuplicateToolAction(val previousState: String) :
    ToolAuthorizationRejected(
        "DUPLICATE_ACTION",
        when (previousState) {
            "PENDING", "APPROVED", "EXECUTING" -> "A matching action is already pending or running. Wait for its existing approval or result before continuing."
            "COMPLETED" -> "A matching action already completed. Read its saved result and use that evidence to continue."
            else -> "The outcome of the earlier matching action is uncertain. Reconcile its saved receipt before deciding what to do next."
        }
    )
