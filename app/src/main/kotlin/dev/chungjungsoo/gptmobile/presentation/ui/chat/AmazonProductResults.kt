package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.amazon.AmazonProductHistoryViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.amazon.AmazonProductMediaViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.amazon.amazonBitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun amazonProductResults(events: List<ToolEvent>): List<JsonObject> = events
    .filter { it.status == ToolEventStatus.COMPLETED && !it.isError }
    .sortedWith(compareByDescending<ToolEvent> { it.completedAt ?: 0L }.thenByDescending { it.sequence })
    .flatMap { event ->
        val payload = amazonResultPayload(event) ?: return@flatMap emptyList()
        ((payload["products"] as? JsonArray).orEmpty() + (payload["unverifiedProducts"] as? JsonArray).orEmpty())
            .take(100).mapNotNull { it as? JsonObject }.filter { product ->
                val domain = AmazonProducts.text(product, "marketplace") ?: return@filter false
                val id = AmazonProducts.text(product, "asin") ?: return@filter false
                AmazonProducts.productUrl(domain, id) == AmazonProducts.text(product, "url") &&
                    AmazonProducts.text(product, "title") != null &&
                    AmazonProducts.hasPrice(product) &&
                    (AmazonProducts.text(payload, "marketplace")?.let { it == domain } != false)
            }
    }
    .distinctBy { listOf(AmazonProducts.text(it, "marketplace"), AmazonProducts.text(it, "asin"), AmazonProducts.text(it, "seller"), AmazonProducts.text(it, "condition"), AmazonProducts.text(it, "variant")) }
    .take(30)

private fun amazonResultPayload(event: ToolEvent): JsonObject? {
    val raw = event.recoveryResult()?.takeIf { it.length <= 1_500_000 } ?: return null
    val payload = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    return payload.takeIf { AmazonProducts.text(it, "schema") == AmazonProducts.SCHEMA }
}

internal fun amazonResultNotice(events: List<ToolEvent>): String? {
    val payload = events.filter { it.status in setOf(ToolEventStatus.COMPLETED, ToolEventStatus.FAILED) }
        .sortedWith(compareByDescending<ToolEvent> { it.completedAt ?: 0L }.thenByDescending { it.sequence })
        .firstNotNullOfOrNull(::amazonResultPayload) ?: return null
    if (((payload["products"] as? JsonArray).orEmpty() + (payload["unverifiedProducts"] as? JsonArray).orEmpty()).filterIsInstance<JsonObject>().any(AmazonProducts::hasPrice)) return null
    val code = (payload["errors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstNotNullOfOrNull { AmazonProducts.text(it, "code") }
    return when (code) {
        "CHALLENGE_REQUIRED" -> "Amazon blocked this lookup. You can open Amazon directly or try again later."
        "RATE_LIMITED", "COOLDOWN_ACTIVE" -> "Amazon temporarily limited searches. Try again after the cooldown."
        "QUOTA_EXCEEDED" -> "The daily Amazon lookup allowance has been reached."
        "PLUGIN_DISABLED" -> "Enable Amazon Research for this AI profile to search for products."
        "PRICE_UNAVAILABLE" -> "Amazon did not supply a price in a confirmed currency. Try searching without price filters."
        "PARSE_CHANGED" -> "Amazon returned an unsupported page layout. No product prices could be verified."
        "TIMEOUT", "NETWORK_ERROR" -> "Amazon could not complete this lookup. Check your connection and try again later."
        "OUTPUT_LIMITED" -> "Product results exceeded the output limit. Increase the Amazon plugin output limit."
        else -> if (AmazonProducts.text(payload, "status") == "failure") "Amazon could not return product listings for this request." else "No products matched this Amazon search. Try a broader search."
    }
}

internal fun amazonProductPrice(product: JsonObject): String {
    val currency = AmazonProducts.text(product, "currency")
    val display = AmazonProducts.text(product, "price")?.takeIf { AmazonProducts.hasPrice(JsonObject(product - "priceAmount")) }
    return (display ?: AmazonProducts.text(product, "priceAmount"))?.let { price ->
        if (currency != null && currency !in price) "$price $currency" else price
    } ?: "Price unavailable"
}

@Composable
internal fun AmazonProductResults(
    toolEvents: List<ToolEvent>,
    modifier: Modifier = Modifier,
    ownerProfileUid: String? = null,
    profilesByRun: Map<String, String> = emptyMap(),
    conversationId: Int? = null,
    debugMode: Boolean = false
) {
    val products = remember(toolEvents) { amazonProductResults(toolEvents) }
    val owners = remember(toolEvents, products, profilesByRun, ownerProfileUid) {
        products.map { product ->
            toolResultOwner(toolEvents, profilesByRun, ownerProfileUid) { event ->
                amazonProductResults(listOf(event)).any { candidate ->
                    listOf("marketplace", "asin", "seller", "condition", "variant").all { AmazonProducts.text(product, it) == AmazonProducts.text(candidate, it) }
                }
            }
        }
    }
    val notice = remember(toolEvents) { amazonResultNotice(toolEvents) }
    if (products.isEmpty() && notice == null) return
    var selectedIndex by remember(products) { mutableStateOf<Int?>(null) }
    val media: AmazonProductMediaViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "amazon-media-$ownerProfileUid-$conversationId")
    if (products.isNotEmpty()) {
        val history: AmazonProductHistoryViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "amazon-history-$ownerProfileUid")
        LaunchedEffect(products, owners, conversationId) {
            products.indices.groupBy { owners[it] }.forEach { (owner, indexes) ->
                val group = indexes.map(products::get)
                history.preload(owner, group)
                media.preload(owner, conversationId, group)
            }
        }
        selectedIndex?.let { index ->
            dev.chungjungsoo.gptmobile.presentation.ui.amazon.AmazonProductDetailDialog(
                products = products,
                selectedIndex = index,
                owner = owners.getOrNull(index),
                conversationId = conversationId,
                onSelect = { selectedIndex = it },
                onDismiss = { selectedIndex = null }
            )
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.mcp_brand_amazon), null, tint = MaterialTheme.colorScheme.primary)
            Text(if (products.isEmpty()) "Amazon search" else "Amazon products · ${products.size}", style = MaterialTheme.typography.titleSmall)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (products.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(products) { index, product -> AmazonProductCard(product, debugMode, media, owners.getOrNull(index), conversationId) { selectedIndex = index } }
            }
        }
    }
}

@Composable
private fun AmazonProductCard(product: JsonObject, debugMode: Boolean, media: AmazonProductMediaViewModel, owner: String?, chatId: Int?, onClick: () -> Unit) {
    val domain = AmazonProducts.text(product, "marketplace").orEmpty()
    val id = AmazonProducts.text(product, "asin").orEmpty()
    if (AmazonProducts.productUrl(domain, id) == null) return
    val nativePreview = AmazonProducts.text(product, "provider") == "free_native"
    val bytes by produceState<ByteArray?>(null, product, owner, chatId) { value = media.thumbnail(owner, chatId, product) }
    val photo by amazonBitmap(bytes, 512)
    Card(
        onClick = onClick,
        modifier = Modifier.width(272.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f))
    ) {
        Surface(Modifier.fillMaxWidth().height(156.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                photo.image?.let { Image(it, "Amazon product photo", Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Fit) }
                    ?: Text("Photo loading / unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (debugMode && nativePreview) "$domain · Public-page preview" else domain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if ((product["sponsored"] as? JsonPrimitive)?.booleanOrNull == true) Text("Sponsored", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(AmazonProducts.text(product, "title").orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(amazonProductPrice(product), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            AmazonProducts.text(product, "rating")?.let { rating ->
                val reviews = AmazonProducts.text(product, "reviewCount")?.let { " · $it reviews" }.orEmpty()
                Text("★ $rating / 5$reviews", style = MaterialTheme.typography.bodySmall)
            }
            listOf("variant", "condition", "seller", "availability", "coupon").forEach { key ->
                AmazonProducts.text(product, key)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if ((product["prime"] as? JsonPrimitive)?.booleanOrNull == true) Text("Prime", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
