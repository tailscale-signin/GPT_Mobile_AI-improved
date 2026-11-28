package dev.chungjungsoo.gptmobile.data.voice

/** An explicit slider edit belongs to the next observed output route, never the last idle device. */
internal class VoiceCaptionRouteCalibration {
    internal data class Write(val routeKey: String, val offsetMs: Int)
    internal data class Resolution(val offsetMs: Int, val write: Write? = null)

    private var pending: Int? = null
    private val recent = linkedMapOf<String, Int>()

    val hasPending: Boolean @Synchronized get() = pending != null

    /** Preference hydration may replace the default, but cannot create a route-specific edit. */
    @Synchronized fun configureDefault(milliseconds: Int) {
        if (pending != milliseconds.coerceIn(-500, 500)) pending = null
    }

    @Synchronized fun request(milliseconds: Int) {
        pending = milliseconds.coerceIn(-500, 500)
    }

    /** Call only after AudioTrack identifies its current route. No route means the edit stays queued. */
    @Synchronized fun observed(routeKey: String, saved: () -> Int): Resolution {
        require(routeKey.isNotBlank())
        val requested = pending
        if (requested != null) {
            pending = null
            recent.remove(routeKey)
            recent[routeKey] = requested
            while (recent.size > 32) recent.remove(recent.keys.first())
            return Resolution(requested, Write(routeKey, requested))
        }
        // A long native stream can delay the persistence worker. Its latest explicit edits
        // still apply if the user changes output devices before that worker becomes free.
        return Resolution(recent[routeKey] ?: saved().coerceIn(-500, 500))
    }
}
