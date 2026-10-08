package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun amazonProductResults(events: List<ToolEvent>): List<JsonObject> = events
    .filter { it.status == ToolEventStatus.COMPLETED && !it.isError }
    .sortedWith(compareByDescending<ToolEvent> { it.completedAt ?: 0 }.thenByDescending { it.sequence })
    .flatMap { event ->
        val raw = event.recoveryResult()?.takeIf { it.length <= 1_500_000 } ?: return@flatMap emptyList()
        val payload = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@flatMap emptyList()
        if (AmazonProducts.text(payload, "schema") != AmazonProducts.SCHEMA) return@flatMap emptyList()
        (payload["products"] as? JsonArray).orEmpty().take(100).mapNotNull { it as? JsonObject }.filter { product ->
            val domain = AmazonProducts.text(product, "marketplace") ?: return@filter false
            val id = AmazonProducts.text(product, "asin") ?: return@filter false
            AmazonProducts.productUrl(domain, id) == AmazonProducts.text(product, "url") && AmazonProducts.text(product, "title") != null
        }
    }
    .distinctBy { listOf(AmazonProducts.text(it, "marketplace"), AmazonProducts.text(it, "asin"), AmazonProducts.text(it, "seller"), AmazonProducts.text(it, "condition"), AmazonProducts.text(it, "variant")) }
    .take(30)

@Composable
internal fun AmazonProductResults(toolEvents: List<ToolEvent>, modifier: Modifier = Modifier, ownerProfileUid: String? = null) {
    val products = remember(toolEvents) { amazonProductResults(toolEvents) }
    if (products.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.mcp_brand_amazon), null, tint = MaterialTheme.colorScheme.primary)
            Text("Amazon products", style = MaterialTheme.typography.titleSmall)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(products) { product -> AmazonProductCard(product, ownerProfileUid) }
        }
        Text("Prices and availability may change at checkout.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AmazonProductCard(product: JsonObject, ownerProfileUid: String?) {
    val context = LocalContext.current
    var openFailed by remember(product) { mutableStateOf(false) }
    var historyOpen by remember(product) { mutableStateOf(false) }
    val domain = AmazonProducts.text(product, "marketplace").orEmpty()
    val id = AmazonProducts.text(product, "asin").orEmpty()
    val canonical = AmazonProducts.productUrl(domain, id) ?: return
    val affiliate = AmazonProducts.text(product, "affiliateUrl")?.let { AmazonProducts.affiliateUrl(it, domain, id) }
    val url = affiliate ?: canonical
    val timestamp = AmazonProducts.text(product, "observedAt", "retrievedAt")?.let { raw ->
        runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(raw)) }.getOrNull()
    }
    val nativePreview = AmazonProducts.text(product, "provider") == "free_native"
    val market = dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket.fromDomain(domain)
    if (historyOpen && nativePreview && market != null && ownerProfileUid != null) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(onDismissRequest = { historyOpen = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            dev.chungjungsoo.gptmobile.presentation.ui.amazon.AmazonDataScreen(
                onBack = { historyOpen = false },
                viewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "amazon-data-$ownerProfileUid-$domain-$id"),
                initialOwner = ownerProfileUid,
                initialMarket = market,
                initialAsin = id
            )
        }
    }
    Card(
        modifier = Modifier.width(272.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (nativePreview) "$domain · Public-page preview" else "$domain · via ${AmazonProducts.text(product, "provider").orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if ((product["sponsored"] as? JsonPrimitive)?.booleanOrNull == true) Text("Sponsored", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(AmazonProducts.text(product, "title").orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val currency = AmazonProducts.text(product, "currency")
            val price = AmazonProducts.text(product, "price")?.let { if (currency != null && currency !in it) "$it $currency" else it } ?: "Price unavailable"
            Text(price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (nativePreview && currency == null && AmazonProducts.text(product, "price") != null) {
                Text("Currency unconfirmed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (nativePreview) Text("Delivery, tax and coupons unconfirmed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AmazonProducts.text(product, "rating")?.let { rating ->
                val reviews = AmazonProducts.text(product, "reviewCount")?.let { " · $it reviews" }.orEmpty()
                Text("★ $rating / 5$reviews", style = MaterialTheme.typography.bodySmall)
            }
            listOf("variant", "condition", "seller", "availability", "coupon").forEach { key ->
                AmazonProducts.text(product, key)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if ((product["prime"] as? JsonPrimitive)?.booleanOrNull == true) Text("Prime", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            timestamp?.let { Text("${if ("observedAt" in product) "Observed" else "Retrieved"} $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (affiliate != null) Text("Affiliate link · supports the server's configured Associate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (nativePreview && ownerProfileUid != null && market != null) {
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { historyOpen = true }) { Text("History & manual watch") }
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                openFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
            }) { Text("Open on Amazon") }
            if (openFailed) Text("No app could open this product link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
