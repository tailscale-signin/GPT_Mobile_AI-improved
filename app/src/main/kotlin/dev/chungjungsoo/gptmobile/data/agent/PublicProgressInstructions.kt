package dev.chungjungsoo.gptmobile.data.agent

/** Public commentary belongs to the primary prompt, outside the bounded repository snapshot. */
internal fun toolProgressInstructions(githubMode: Boolean, compact: Boolean = false): String {
    val ordinaryRequests = "\nAnswer ordinary general-knowledge and writing requests directly when tools add no needed evidence. Do not call discover_available_tools just to check what exists."
    return ordinaryRequests + if (githubMode && compact) {
        "\nUse <progress_update>...</progress_update> for material findings, decisions or blockers, then continue. Be brief and concrete; preserve evidence scope and units. Keep the final answer separate. Never reveal private reasoning or claim unverified actions, fixes, tests or completion."
    } else if (githubMode) {
        "\n" + GITHUB_PUBLIC_PROGRESS_INSTRUCTION
    } else {
        "\nBefore the first tool call and after every 10 completed tool calls, " + ToolProgressTracker.SUMMARY_INSTRUCTION
    }
}

private const val GITHUB_PUBLIC_PROGRESS_INSTRUCTION =
    "Public previews: when you find something material, complete a meaningful step, change the plan or encounter a new blocker, " +
        "write <progress_update>...</progress_update> in your normal response, then continue the work. " +
        "Use one to three brief, natural sentences. Lead with the specific finding or decision, explain its practical effect, " +
        "and name the next useful action when it adds information. Describe what changed since your last update; " +
        "omit an update when nothing material changed. Avoid repeated introductions, generic reassurance, tool-call recaps " +
        "and headings such as Status Update or Repository findings. Use Markdown headings, **emphasis**, bullets or a compact table " +
        "when they make the update easier to read. Statistics must come from supplied evidence and keep their units, source and scope; " +
        "never estimate missing results. The app renders graphs from recorded check tables; do not invent chart data or draw ASCII graphs. " +
        "Distinguish inspected, proposed, applied and tested work, and pending, skipped and failed checks. " +
        "Never claim unverified fixes or completion, reveal private reasoning, credentials or raw tool payloads. " +
        "Close the progress tag before continuing. Keep the final answer separate."
