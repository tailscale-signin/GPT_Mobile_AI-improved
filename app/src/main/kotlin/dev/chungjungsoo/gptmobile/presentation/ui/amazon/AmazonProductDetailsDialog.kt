package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonPublicPriceHistoryClient
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun AmazonProductDetailsDialog(product: JsonObject, owner: String?, chatId: Int?, onDismiss: () -> Unit) {
    val key = "amazon-product-$owner-${AmazonProducts.text(product, "marketplace")}-${AmazonProducts.text(product, "asin")}-${product.hashCode()}"
    val viewModel: AmazonProductDetailsViewModel = hiltViewModel(key = key)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(product, owner, chatId) { viewModel.open(product, owner, chatId) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val visible = state.product.takeIf { it.isNotEmpty() } ?: product
    val context = LocalContext.current
    val market = AmazonProducts.text(visible, "marketplace").orEmpty()
    val asin = AmazonProducts.text(visible, "asin").orEmpty()
    val canonical = AmazonProducts.productUrl(market, asin) ?: return
    val link = AmazonProducts.text(visible, "affiliateUrl")?.let { AmazonProducts.affiliateUrl(it, market, asin) } ?: canonical
    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Product details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close product details") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(AmazonProducts.text(visible, "title").orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("$market · $asin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOfNotNull(AmazonProducts.text(visible, "price") ?: "Price unavailable", AmazonProducts.text(visible, "currency")).joinToString(" "), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                listOf("rating" to "Rating", "reviewCount" to "Reviews", "variant" to "Variant", "condition" to "Condition", "seller" to "Seller", "availability" to "Availability", "coupon" to "Coupon", "observedAt" to "Price observed", "provider" to "Provider").forEach { (field, label) ->
                    AmazonProducts.text(visible, field)?.let { Text("$label: $it", style = MaterialTheme.typography.bodySmall) }
                }
                Text("Description", style = MaterialTheme.typography.titleSmall)
                Text((visible["description"] as? JsonPrimitive)?.content?.take(4000) ?: "${AmazonProducts.text(visible, "title").orEmpty()}. Additional description is unavailable from the current provider.", style = MaterialTheme.typography.bodyMedium)
                (visible["features"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().forEach { Text("• ${it.content}", style = MaterialTheme.typography.bodyMedium) }
                HorizontalDivider()
                Text("Price history", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                val external = state.publicChart
                val bitmap = remember(external) { external?.png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                if (bitmap != null) {
                    Image(bitmap, "Keepa price history for $asin, last ${external?.reference?.rangeDays} days", Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), contentScale = ContentScale.Fit)
                    Text("Keepa · last ${external?.reference?.rangeDays} days · retrieved ${external?.retrievedAt}", style = MaterialTheme.typography.labelSmall)
                }
                AmazonPublicPriceHistoryClient.reference(market, asin)?.let { reference ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reference.sourceUrl))) }.onFailure { Toast.makeText(context, "No app could open the history provider.", Toast.LENGTH_SHORT).show() }
                    }) { Text("Open price history on Keepa") }
                }
                state.historyNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Local observations", style = MaterialTheme.typography.titleSmall)
                if (state.observations.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No confirmed price observations yet. History builds from successful lookups on this device.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    state.observations.groupBy { it.seriesKey }.values.forEach { points ->
                        Text(points.first().sourceType.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                        AmazonObservationPlot(points)
                    }
                }
                Text("Local observations only. Separate providers and offers have separate graphs; gaps do not establish historical lows.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }.onFailure { Toast.makeText(context, "No app could open this product link.", Toast.LENGTH_SHORT).show() }
                }) { Text("Open on Amazon") }
            }
        }
    }
}
