package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Only whole, date-only questions are local; embedded questions still go to the writer. */
internal object DeviceDateAnswer {
    fun answer(request: String, instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val text = request.trim().lowercase(Locale.ROOT).replace("’", "'").trimEnd('?', '.', '!').trim()
        if (text !in setOf("what's today", "what is today", "what's today's date", "what is today's date", "what is the date today", "what's the date today", "today's date", "what day is it today", "what day is today", "what day of the week is it", "what day of the week is today", "what is the current date", "current date", "what's the date", "what is the date")) return null
        return "Today is ${instant.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.ENGLISH))} ($zone), according to your device clock."
    }
}
