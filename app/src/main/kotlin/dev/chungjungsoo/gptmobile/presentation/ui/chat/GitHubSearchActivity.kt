package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus

internal data class GitHubSearchActivity(val websites: Int, val brands: List<ChatSourceBrand>, val searching: Boolean) {
    val label get() = if (websites == 0) {
        if (searching) "Searching websites" else "Search complete · no websites found"
    } else if (searching) {
        "Searching $websites ${if (websites == 1) "website" else "websites"}"
    } else {
        "Found $websites ${if (websites == 1) "website" else "websites"}"
    }
}

/** Count distinct observed sites, never a requested result limit or an invented target count. */
internal fun gitHubSearchActivity(events: List<ToolEvent>): GitHubSearchActivity? {
    val relevant = events.filter { !it.toolName.contains("github", true) && Regex("(?i)search|brave|tavily|duckduckgo|crawl|read_url|browse").containsMatchIn(it.toolName) }
    val last = events.lastOrNull() ?: return null
    if (last !in relevant || last.isError || last.status in setOf(ToolEventStatus.FAILED, ToolEventStatus.CANCELED)) return null
    val evidence = collectChatSources("", relevant)
    val sites = evidence.sources.flatMap { listOf(it) + it.similarSources }.distinctBy { it.host }
    val brands = ((evidence.engines.mapNotNull(::chatSearchEngineBrand) + listOfNotNull(chatSearchEngineBrand("${last.connectionNameSnapshot.orEmpty()} ${last.toolName}"))) + sites.mapNotNull { chatSourceBrand(it.host) }).distinctBy { it.id }.take(4)
    return GitHubSearchActivity(sites.size, brands, last.status in setOf(ToolEventStatus.RUNNING, ToolEventStatus.PENDING))
}

@Composable
internal fun GitHubSearchBrandStack(activity: GitHubSearchActivity) {
    val count = activity.brands.size.coerceAtLeast(1)
    Box(Modifier.width((24 + (count - 1) * 15).dp).size(height = 24.dp, width = (24 + (count - 1) * 15).dp)) {
        repeat(count) { index ->
            val brand = activity.brands.getOrNull(index)
            Surface(
                Modifier.offset(x = (index * 15).dp).size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (brand == null) {
                        Icon(Icons.Outlined.Language, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Image(painterResource(brand.icon), null, Modifier.padding(5.dp).size(14.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}
