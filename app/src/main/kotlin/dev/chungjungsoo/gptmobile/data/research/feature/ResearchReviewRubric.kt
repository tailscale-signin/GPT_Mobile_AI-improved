package dev.chungjungsoo.gptmobile.data.research.feature

/** Versioned product rubric, not GRADE, a probability, or a substitute for expert appraisal. */
internal object ResearchReviewRubric {
    const val VERSION = 2
    val supportedVersions = setOf(1, VERSION)

    private val modules = mapOf(
        StudyDesign.RANDOMIZED_TRIAL to listOf(
            "Assess the randomized comparison, sequence generation and allocation concealment for this outcome.",
            "Assess enrollment, baseline imbalances, deviations from allocation and confounding after randomization.",
            "Assess outcome measurement, relevant blinding, intervention fidelity and follow-up.",
            "Assess the analysis population, attrition by arm, missing outcomes, uncertainty and robustness.",
            "Compare reported outcomes and analyses with the protocol or registration; distinguish absent documents from agreement."
        ),
        StudyDesign.OBSERVATIONAL to listOf(
            "Assess the comparison group, temporal order and whether the design supports the stated inference.",
            "Assess sampling, selection bias, measured and unmeasured confounding, and adjustment choices.",
            "Assess exposure and outcome measurement, misclassification and follow-up.",
            "Assess missing data, attrition, model specification, uncertainty and sensitivity analyses.",
            "Assess consistency of prespecified and reported analyses; retain associations as associations."
        ),
        StudyDesign.DIAGNOSTIC to listOf(
            "Assess the index test, reference standard and appropriateness of their comparison.",
            "Assess patient spectrum, recruitment and exclusions affecting the intended population.",
            "Assess masking, prespecified thresholds, timing and whether all participants receive the reference standard.",
            "Assess indeterminate results, missing tests, verification bias and uncertainty in accuracy estimates.",
            "Assess consistency of thresholds, outcomes and reporting with any available protocol."
        ),
        StudyDesign.SYSTEMATIC_REVIEW to listOf(
            "Assess the review question, eligibility criteria and relevance of included study designs.",
            "Assess search coverage, selection procedures, study-family overlap and missing studies.",
            "Assess data extraction, outcome comparability and appraisal of included studies.",
            "Assess synthesis methods, heterogeneity, missing results, uncertainty and robustness.",
            "Compare the protocol and reported review, including outcome selection and reporting limitations."
        ),
        StudyDesign.QUALITATIVE to listOf(
            "Assess the fit between the research question, qualitative approach and analytic design.",
            "Assess sampling rationale, recruitment, context and researcher reflexivity.",
            "Assess data collection, participant accounts and documentation of the research process.",
            "Assess the analytic process, divergent accounts, incomplete data and support for interpretations.",
            "Assess transparency, coherence of findings with quotations and limits of transferability."
        ),
        StudyDesign.MACHINE_LEARNING to listOf(
            "Assess the task, evaluation design, comparator baselines and independence of test data.",
            "Assess dataset selection, representativeness, leakage and train/test overlap.",
            "Assess labels, measurement, preprocessing and consistent application of the evaluation procedure.",
            "Assess missing data, tuning isolation, uncertainty, repeated trials and robustness.",
            "Assess reproducibility, external evaluation and consistency of reported metrics with the stated task."
        )
    )

    fun supports(design: StudyDesign): Boolean = modules[design]?.size == ResearchReviewScoreCalculator.reliabilityDomains.size
    fun signalingRules(design: StudyDesign): Map<String, String> = modules[design]?.let {
        ResearchReviewScoreCalculator.reliabilityDomains.zip(it).toMap()
    }.orEmpty()
}
