package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.ui.chat.amazonProductPrice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Composable
fun AmazonProductDetailDialog(
    products: List<JsonObject>,
    selectedIndex: Int,
    owner: String?,
    conversationId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val product = products.getOrNull(selectedIndex) ?: return
    val model: AmazonProductDetailViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(key = "amazon-product-gallery-$owner-$conversationId")
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(product, owner, conversationId) { model.open(owner, product, conversationId) }
    DisposableEffect(model) { onDispose { model.close() } }
    val displayedState = state.takeIf {
        listOf("marketplace", "asin", "variant", "seller", "condition").all { field -> AmazonProducts.text(it.product, field) == AmazonProducts.text(product, field) }
    } ?: AmazonProductDetailState(product, loading = true, historyLoading = true, imageLoading = true)
    AmazonProductDetailContent(product, displayedState, selectedIndex, products.size, onSelect, onDismiss)
}

@Composable
internal fun AmazonProductDetailContent(
    product: JsonObject,
    state: AmazonProductDetailState,
    selectedIndex: Int = 0,
    productCount: Int = 1,
    onSelect: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    val displayed = state.product.takeUnless { it.isEmpty() } ?: product
    val domain = AmazonProducts.text(displayed, "marketplace").orEmpty()
    val asin = AmazonProducts.text(displayed, "asin").orEmpty()
    val title = AmazonProducts.text(displayed, "title").orEmpty()
    val uri = LocalUriHandler.current
    var openFailed by remember(domain, asin) { mutableStateOf(false) }
    val photo by amazonBitmap(state.image, 1024)
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) { AmazonGraphPalette(scheme.surfaceContainerHigh.toArgb(), scheme.onSurface.toArgb(), scheme.primary.toArgb(), scheme.secondary.toArgb(), scheme.tertiary.toArgb(), scheme.error.toArgb()) }
    val graph by amazonBitmap(state.publicHistory?.png, 1000, palette)
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) { listState.scrollToItem(0) }
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val description = remember(displayed) { amazonProductDescription(displayed) }
    val highlights = remember(displayed) { amazonProductHighlights(displayed) }
    val facts = remember(displayed) { amazonProductFacts(displayed) }
    val affiliate = AmazonProducts.text(displayed, "affiliateUrl")?.let { AmazonProducts.affiliateUrl(it, domain, asin) }
    val link = affiliate ?: AmazonProducts.productUrl(domain, asin)
    val dialogHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.9f).coerceAtMost(760.dp)

    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.testTag("amazon-product-gallery").padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = dialogHeight)
                .pointerInput(selectedIndex, productCount, swipeThreshold) {
                    if (productCount > 1) {
                        var distance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { distance = 0f },
                            onDragCancel = { distance = 0f },
                            onDragEnd = {
                                val next = amazonProductSwipeTarget(selectedIndex, productCount, distance, swipeThreshold)
                                if (next != selectedIndex) onSelect(next)
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                distance += amount
                            }
                        )
                    }
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.mcp_brand_amazon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Product details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close product details") }
                }
                if (productCount > 1) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { onSelect(selectedIndex - 1) }, enabled = selectedIndex > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous Amazon product") }
                        Text("${selectedIndex + 1} / $productCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { onSelect(selectedIndex + 1) }, enabled = selectedIndex < productCount - 1) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next Amazon product") }
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    if (photo.image != null || photo.loading || state.imageLoading) {
                        item {
                            Surface(
                                Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = Color.White,
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
                            ) {
                                Box(Modifier.fillMaxWidth().height(200.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                                    photo.image?.let { Image(it, "Product image: $title", Modifier.size(168.dp), contentScale = ContentScale.Fit) }
                                        ?: CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(domain, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(amazonProductPrice(displayed), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            AmazonProducts.text(displayed, "rating")?.let { rating ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                                    Text("$rating / 5", style = MaterialTheme.typography.bodyMedium)
                                    AmazonProducts.text(displayed, "reviewCount")?.let { Text("· $it reviews", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if ((displayed["prime"] as? JsonPrimitive)?.booleanOrNull == true) ProductBadge("Prime")
                                if ((displayed["sponsored"] as? JsonPrimitive)?.booleanOrNull == true) ProductBadge("Sponsored")
                            }
                        }
                    }
                    if (facts.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Product information", style = MaterialTheme.typography.titleMedium)
                                facts.forEach { (label, value) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (description != null || highlights.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("About this item", style = MaterialTheme.typography.titleMedium)
                                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                highlights.forEach { feature ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("•", color = MaterialTheme.colorScheme.primary)
                                        Text(feature, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Price history", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            if (graph.image != null) {
                                Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))) {
                                    Image(requireNotNull(graph.image), "${state.publicHistory?.provider} price history for $title", Modifier.fillMaxWidth().aspectRatio(900f / 320f), contentScale = ContentScale.Fit)
                                }
                            } else if (state.historyLoading || graph.loading) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Loading price history…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(state.publicHistory?.notice ?: "Public price history is unavailable for this product.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            state.publicHistory?.let { history ->
                                TextButton(onClick = { runCatching { uri.openUri(history.sourceUrl) } }) {
                                    Text("View ${history.provider} history")
                                    Icon(Icons.Rounded.OpenInNew, null, Modifier.padding(start = 6.dp).size(14.dp))
                                }
                            }
                        }
                    }
                    state.notice?.let { notice -> item { Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (openFailed) Text("No app could open this product link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (link != null) {
                        Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { openFailed = runCatching { uri.openUri(link) }.isFailure }) {
                            Text("Open on Amazon")
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                    Text(
                        "Prices and availability may change at checkout." + if (affiliate != null) " Affiliate link." else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductBadge(label: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun amazonBitmap(bytes: ByteArray?, maxDimension: Int, palette: AmazonGraphPalette? = null) = key(bytes, maxDimension, palette) {
    produceState(AmazonBitmapState(loading = bytes != null), bytes, maxDimension, palette) {
        val bitmap = bytes?.let {
            withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                if (bounds.outWidth !in 1..16_384 || bounds.outHeight !in 1..16_384) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                if (palette == null) return@withContext decoded.asImageBitmap()
                val pixels = IntArray(decoded.width * decoded.height)
                decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                for (index in pixels.indices) pixels[index] = palette.color(pixels[index])
                val themed = Bitmap.createBitmap(pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                decoded.recycle()
                themed.asImageBitmap()
            }
        }
        value = AmazonBitmapState(image = bitmap)
    }
}

private data class AmazonBitmapState(val image: ImageBitmap? = null, val loading: Boolean = false)
