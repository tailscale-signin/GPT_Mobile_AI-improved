package dev.chungjungsoo.gptmobile.data.agent.tool

internal const val LOCATION_RETENTION_MILLIS = 5 * 60 * 1000L

/** Process-local only. Location is never written to memory, disk, or a model without its tool. */
internal class SharedLocationFix {
    private var fix: DeviceLocation? = null
    private var savedAt = 0L
    private var precisePermission = false

    fun save(location: DeviceLocation, elapsedMillis: Long, precise: Boolean) {
        fix = location
        savedAt = elapsedMillis
        precisePermission = precise
    }

    fun get(elapsedMillis: Long, precise: Boolean): DeviceLocation? {
        if (elapsedMillis - savedAt !in 0 until LOCATION_RETENTION_MILLIS || precise != precisePermission) clear()
        return fix
    }

    fun clear() {
        fix = null
        savedAt = 0L
    }
}
