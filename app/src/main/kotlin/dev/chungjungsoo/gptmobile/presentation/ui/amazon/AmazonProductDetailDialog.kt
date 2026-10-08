package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.ui.chat.amazonProductPrice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun AmazonProductDetailDialog(product: JsonObject, owner: String?, onDismiss: () -> Unit) {
    val domain = AmazonProducts.text(product, "marketplace").orEmpty()
    val asin = AmazonProducts.text(product, "asin").orEmpty()
    val model: AmazonProductDetailViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "amazon-product-$owner-$domain-$asin")
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(product, owner) { model.open(owner, product) }
    DisposableEffect(model) { onDispose { model.close() } }
    val displayed = state.product.takeUnless { it.isEmpty() } ?: product
    val uri = LocalUriHandler.current
    val graph by produceState<ImageBitmap?>(null, state.publicHistory?.png) {
        value = state.publicHistory?.png?.let { bytes -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } }
    }
    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 680.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Amazon product", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close product details") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(AmazonProducts.text(displayed, "title").orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text("$domain · $asin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(amazonProductPrice(displayed), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        listOf("rating", "reviewCount", "brand", "availability", "seller", "condition", "variant", "coupon").forEach { key ->
                            AmazonProducts.text(displayed, key)?.let { Text("${key.replace("reviewCount", "Reviews").replaceFirstChar { it.uppercase() }}: $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text("Description", style = MaterialTheme.typography.titleMedium)
                        Text(AmazonProducts.text(displayed, "description") ?: "${AmazonProducts.text(displayed, "title").orEmpty()}\nA full description was not supplied by this provider.", style = MaterialTheme.typography.bodyMedium)
                        (displayed["features"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().filter { it.isString }.take(12).forEach { Text("• ${it.content}", style = MaterialTheme.typography.bodyMedium) }
                    }
                    item {
                        Text("Price history", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        graph?.let { Image(it, "${state.publicHistory?.provider} price history for $asin", Modifier.fillMaxWidth().aspectRatio(900f / 320f), contentScale = ContentScale.Fit) }
                        val external = state.publicHistory
                        if (external != null) {
                            Text(external.notice ?: "History supplied by ${external.provider}. Prices and availability may change.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { runCatching { uri.openUri(external.sourceUrl) } }) { Text("Open ${external.provider} history") }
                        } else if (!state.loading) {
                            Text("Public price history is unavailable.", style = MaterialTheme.typography.bodySmall)
                        }
                        val local = state.localHistory?.observations.orEmpty()
                        if (local.isNotEmpty()) {
                            Text("Your local observations", style = MaterialTheme.typography.titleSmall)
                            AmazonObservationPlot(local)
                            Text("${local.size} observed prices · separate from the provider's historical chart.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    item {
                        val canonical = AmazonProducts.productUrl(domain, asin)
                        val link = AmazonProducts.text(displayed, "affiliateUrl")?.let { AmazonProducts.affiliateUrl(it, domain, asin) } ?: canonical
                        if (link != null) TextButton(modifier = Modifier.fillMaxWidth(), onClick = { runCatching { uri.openUri(link) } }) { Text("Open on Amazon") }
                        Text("Prices and availability may change at checkout.", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
