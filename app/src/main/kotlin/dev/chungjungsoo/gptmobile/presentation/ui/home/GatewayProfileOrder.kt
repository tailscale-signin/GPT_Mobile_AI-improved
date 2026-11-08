package dev.chungjungsoo.gptmobile.presentation.ui.home

/** Fresh availability comes first; the user's sort, including favorites, applies inside each group. */
internal fun promoteAvailableProfileUids(base: List<String>, available: Set<String>, favoritesFirst: Set<String>? = null): List<String> =
    base.sortedWith(compareBy<String> { if (it in available) 0 else 1 }.thenBy { if (favoritesFirst == null || it in favoritesFirst) 0 else 1 })

/** Membership can change immediately; existing targets never move underneath an interaction. */
internal fun retainProfileOrder(previous: List<String>, desired: List<String>, interacting: Boolean): List<String> {
    if (!interacting || previous.isEmpty()) return desired
    val wanted = desired.toSet()
    val retained = previous.filter { it in wanted }
    val existing = retained.toSet()
    return retained + desired.filter { it !in existing }
}
