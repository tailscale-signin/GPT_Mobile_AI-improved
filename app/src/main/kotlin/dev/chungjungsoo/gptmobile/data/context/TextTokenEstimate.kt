package dev.chungjungsoo.gptmobile.data.context

/** Shared conservative UTF-8 estimate used by prompt admission and memory selection. */
object TextTokenEstimate {
    fun estimate(text: String): Int = ((text.toByteArray(Charsets.UTF_8).size.toLong() + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
