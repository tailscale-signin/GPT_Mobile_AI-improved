package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.util.Locale

/** Labels are derived from the tool identity, never its untrusted result text. */
internal fun friendlyToolActivity(toolName: String): String {
    val name = toolName.lowercase(Locale.ROOT)
    return when {
        "nearby" in name || "places" in name -> "Finding nearby places"
        "route" in name || "directions" in name -> "Planning your route"
        "reverse_geocode" in name -> "Finding the address"
        "geocode" in name -> "Looking up an address"
        "location" in name || name.endsWith("gps") -> "Finding location"
        "search" in name -> "Searching " + when {
            "web" in name -> "the web"
            "code" in name -> "code"
            "file" in name -> "files"
            else -> "for information"
        }
        "read_url" in name || "fetch_url" in name || "crawl" in name -> "Reading a web page"
        "read_file" in name || "file_contents" in name -> "Reading a file"
        "memory" in name || "recall" in name -> "Checking saved memories"
        "calculate" in name -> "Calculating"
        "weather" in name -> "Checking the weather"
        "date" in name || "time" in name -> "Checking the time"
        else -> toolName.substringAfterLast("__").replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("[_:./-]+"), " ").trim().take(80).ifBlank { "Running tool" }
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
