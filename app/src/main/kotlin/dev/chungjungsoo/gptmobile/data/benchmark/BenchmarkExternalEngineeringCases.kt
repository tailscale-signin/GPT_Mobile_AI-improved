package dev.chungjungsoo.gptmobile.data.benchmark

/** Public task identities only. Executable prompts, hidden tests and grader hashes are frozen by the host. */
internal fun benchmarkExternalEngineeringCases(lane: BenchmarkEngineeringLane, preset: String = "quick", seed: Int = 17, taskPack: String = "coding"): List<BenchmarkCase> {
    require(lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB))
    val variants = when (preset) {
        "quick" -> 1
        "standard" -> 3
        "deep" -> 8
        else -> error("Choose Coding Quick, Standard or Deep.")
    }
    require(taskPack in setOf("coding", "repository"))
    val families = linkedMapOf(
        "pagination" to "Merge paginated records",
        "idempotency" to "Reconcile uncertain operations",
        "dependencies" to "Schedule dependency waves",
        "budget" to "Enforce execution budgets",
        "streaming" to "Assemble streamed responses",
        "migration" to "Preserve migrated records",
        "patches" to "Apply exact source patches",
        "ci" to "Verify current CI evidence",
        "retries" to "Bound retry decisions",
        "permissions" to "Enforce permission boundaries",
        "evidence" to "Retain qualified evidence",
        "graph" to "Traverse dependency graphs"
    )
    fun descriptor(id: String, family: String, label: String) = BenchmarkCase(
        id = "engineering:${lane.name.lowercase()}:$id",
        label = label,
        category = "engineering",
        prompt = "Resolve immutable task $id from the selected execution host before model dispatch.",
        workload = lane.evidenceClass,
        seed = seed,
        validatorVersion = "independent-external-grader-v2",
        outputPolicy = "EXTERNAL_ARTIFACT_RECEIPT_V1",
        engineeringLane = lane,
        engineeringTaskId = id,
        engineeringFamilyId = family
    )
    if (taskPack == "repository") {
        val native = listOf(
            "kotlin-stale-generation" to "Kotlin: discard stale generations",
            "kotlin-stable-selection" to "Compose state: stable item selection",
            "kotlin-event-replay" to "Kotlin: prevent event replay",
            "kotlin-state-restore" to "Compose state: restore saved state",
            "room-add-default" to "Room SQL: migrate required defaults",
            "room-rebuild-foreign-key" to "Room SQL: preserve foreign keys",
            "room-deduplicate-index" to "Room SQL: reconcile unique indexes",
            "room-transaction-rollback" to "Room SQL: atomic migration rollback",
            "ci-required-needs" to "Actions YAML: require dependency checks",
            "ci-skipped-gate" to "Actions YAML: reject skipped gates",
            "ci-event-ref" to "Actions YAML: bind the exact event revision",
            "ci-fork-permissions" to "Actions YAML: scope fork permissions"
        )
        val selected = when (preset) {
            "quick" -> listOf(0, 4, 8, 9)
            "standard" -> listOf(0, 4, 8, 9, 1, 5, 6, 10)
            else -> native.indices.toList()
        }
        return selected.map { index ->
            val (id, title) = native[index]
            descriptor("repository-$id", "repository-$id", title)
        }
    }
    return buildList {
        families.forEach { (family, title) ->
            repeat(variants) { index -> add(descriptor("$family-${index + 1}", family, "$title · ${index + 1}")) }
        }
        if (preset == "deep") {
            listOf("publication", "planning", "transaction", "recovery").forEach { name ->
                add(descriptor("capstone-$name", "capstone-$name", "Composed ${name.replaceFirstChar(Char::uppercaseChar)} task"))
            }
        }
    }
}
