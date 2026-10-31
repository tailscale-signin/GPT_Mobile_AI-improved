package dev.chungjungsoo.gptmobile.data.github

/** Wall-clock run timestamps survive recreation, tool rounds, pauses and process recovery. */
internal fun githubElapsedTime(startMillis: Long, endMillis: Long): String {
    val seconds = ((endMillis.coerceAtLeast(0) - startMillis.coerceAtLeast(0)).coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
