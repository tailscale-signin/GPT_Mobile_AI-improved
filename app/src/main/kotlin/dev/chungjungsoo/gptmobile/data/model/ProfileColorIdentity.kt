package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

/** Stable identity is persisted separately from the theme-dependent foreground RGB. */
@Serializable
data class ProfileColorIdentity(val version: Int = 1, val hue: Int, val marker: Int)

object ProfileColorRegistry {
    fun ensure(existing: Map<String, ProfileColorIdentity>, profileUids: Collection<String>): Map<String, ProfileColorIdentity> {
        val result = existing.toMutableMap()
        val used = existing.values.map { it.hue }.toMutableSet()
        var marker = (existing.values.maxOfOrNull { it.marker } ?: 0).coerceAtLeast(0)
        profileUids.distinct().sorted().filter { it !in result }.forEach { uid ->
            val initial = (researchHash(uid).take(8).toLong(16) % 360).toInt()
            // Coprime step visits every hue; existing assignments never move when profiles change.
            val hue = (0 until 360).map { (initial + it * 137) % 360 }.firstOrNull { it !in used } ?: initial
            marker = Math.addExact(marker, 1)
            result[uid] = ProfileColorIdentity(hue = hue, marker = marker)
            used += hue
        }
        return result
    }
}
