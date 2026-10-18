package dev.chungjungsoo.gptmobile.data.research.delivery

internal enum class ResearchReportContract(val sections: List<String>, val guidance: String) {
    GENERAL(
        listOf("Executive findings", "Background", "Key evidence", "Recent developments", "Comparison of evidence", "Limitations", "Open questions", "References"),
        "Explain the findings in terms appropriate to this task. Identify dates, primary sources, disagreements, missing evidence and uncertainty. Do not impose clinical or experimental terminology on unrelated subjects."
    ),
    TECHNICAL(
        listOf("Executive findings", "Technical context", "Architecture and implementation", "Current capabilities", "Comparison of evidence", "Risks and limitations", "Recommendations", "References"),
        "Explain architecture, compatibility, implementation constraints, versions, tradeoffs, reproducible tests and engineering recommendations. Distinguish documented support from measured behavior."
    ),
    PRODUCT(
        listOf("Executive findings", "Requirements", "Available options", "Current specifications", "Comparison of evidence", "Costs and limitations", "Recommendations", "References"),
        "Compare products against the user's requirements. Attribute specifications, availability and prices to dated evidence; distinguish verified measurements from marketing claims."
    ),
    SCIENTIFIC(
        ResearchDeliveryPacket.SECTIONS,
        "Explain leading and latest studies with authors, journal, publication date, design, participants enrolled versus analyzed, intervention/comparator, numerical outcomes and units, uncertainty and limitations. Unknown fields remain unknown. Discuss negative, null and mixed results and safety only where documented; never generalize between populations or interventions."
    );

    companion object {
        fun forTask(task: String): ResearchReportContract {
            val value = task.lowercase()
            fun matches(pattern: String) = Regex(pattern).containsMatchIn(value)
            return when {
                matches("\\b(clinical|patients?|trials?|medicine|medical|therapy|disease|health|biomedical|randomi[sz]ed|systematic review|meta.analysis)\\b") -> SCIENTIFIC
                matches("\\b(software|engineering|code|api|android|kotlin|runtime|llama|litert|qnn|architecture|implementation|algorithm)\\b") -> TECHNICAL
                matches("\\b(buy|buying|price|products?|shopping|laptop|phones?|headphones?|purchase)\\b") -> PRODUCT
                else -> GENERAL
            }
        }
    }
}
