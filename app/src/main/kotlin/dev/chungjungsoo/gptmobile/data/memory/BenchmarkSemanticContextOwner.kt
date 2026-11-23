package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission

/** Context preparation shares its own real benchmark lease; callers cannot supply an owner flag. */
internal suspend fun benchmarkSemanticContextOwner(scope: String): Boolean =
    scope.startsWith("github:benchmark:") &&
        scope.length <= 256 &&
        InferenceAdmission.currentLease()?.isolatedMeasurement == true
