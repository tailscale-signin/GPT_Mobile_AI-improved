package dev.chungjungsoo.gptmobile.data.agent.provider

/** A removed tool schema must not leave historical tool calls as the only instruction. */
internal fun toolRoundSystemPrompt(prompt: String?, hasTools: Boolean, constraints: RequestConstraints = RequestConstraints()): String? {
    // Combined writers already freeze their complete editorial instructions before dispatch.
    // Preserve those exact bytes for initial delivery, repair and continuation; tool access
    // remains disabled by the request constraints and provider tool-choice fields.
    if (hasTools || constraints.requestRole == "synthesis" || constraints.combined?.role?.finalization == true) return prompt
    return listOfNotNull(
        prompt?.takeIf { it.isNotBlank() },
        "No tools are available for this request. Return the requested answer format directly, using only the conversation and completed results already supplied. Do not emit tool calls, function invocations, or wire-control markup. Do not claim that an unexecuted action succeeded. If evidence is insufficient, state what remains unresolved."
    ).joinToString("\n\n")
}
