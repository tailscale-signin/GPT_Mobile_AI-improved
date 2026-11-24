package dev.chungjungsoo.gptmobile.data.github

/** User-selected preflight scope; choosing read-only never changes saved approval authority. */
internal enum class GitHubFixtureTestMode(val label: String, val description: String) {
    SUPPORTED_FIXTURES(
        "Test supported disposable fixtures",
        "The task checks its saved fixture approval policy before creating anything. General tool auto-approval does not approve fixture changes. Completed reads remain available if a fixture decision is needed."
    ),
    EXISTING_READ_ONLY(
        "Inspect existing fixtures only",
        "Run read-only checks against existing resources. Creation, changes, publication and cleanup are outside this run; their coverage remains untested."
    );

    fun prompt(original: String): String = when (this) {
        SUPPORTED_FIXTURES -> original
        EXISTING_READ_ONLY -> GitHubWorkflowPreset.selected("test_integration").copy(
            objective = "Test the authorized GitHub integration in read-only mode using existing resources and fixtures. Preflight the exact repository revision, authorized catalog, model context capacity and available workflow evidence. Do not create, change, publish or delete fixture resources. Reuse completed read receipts and report missing prerequisites explicitly.",
            acceptance = listOf(
                "Discover the authorized catalog and exercise representative read-only operations",
                "Inspect existing fixture evidence at its exact revision without performing mutations",
                "Report mutation capabilities OUT_OF_SCOPE and missing existing inputs BLOCKED or NOT_TESTED",
                "Preserve completed reads and exact continuations for a later separately authorized task",
                "Keep live account, device and mocked test evidence separate"
            )
        ).prompt()
    }
}
