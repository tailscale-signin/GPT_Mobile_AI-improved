package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.agent.tool.MapCoordinate
import dev.chungjungsoo.gptmobile.data.agent.tool.NearbyPlace
import dev.chungjungsoo.gptmobile.data.agent.tool.distanceMeters
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.amazon.amazonBitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun airbnbListingResults(events: List<ToolEvent>): List<AirbnbListing> = events
    .filter { it.status == ToolEventStatus.COMPLETED && !it.isError }
    .flatMap { event ->
        val raw = event.recoveryResult()?.takeIf { it.length <= 1_500_000 } ?: return@flatMap emptyList()
        val payload = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@flatMap emptyList()
        if (payload["schema"] != JsonPrimitive(AirbnbListings.SCHEMA)) return@flatMap emptyList()
        AirbnbListings.normalize(payload)
    }.groupBy(AirbnbListings::stayKey).values.map { it.reduce(AirbnbListings::merge) }.take(30)

/** Only provider coordinate pairs become pins; a city name is never a listing location. */
internal fun airbnbMapData(listings: List<AirbnbListing>, position: MapCoordinate?): LocationMapData? {
    val located = listings.mapNotNull { listing ->
        val lat = listing.latitude ?: return@mapNotNull null
        val lon = listing.longitude ?: return@mapNotNull null
        MapCoordinate(lat, lon).takeIf { it.isValid }?.let { listing to it }
    }
    if (located.isEmpty()) return null
    val userPosition = position?.takeIf { it.isValid }
    val center = userPosition ?: located.first().second
    val places = located.map { (listing, coordinate) ->
        NearbyPlace(listing.id, listing.title, coordinate.latitude, coordinate.longitude, userPosition?.let { distanceMeters(it, coordinate) } ?: 0.0)
    }.distinctBy { it.id }.let { if (userPosition != null) it.sortedBy { place -> place.distanceMeters } else it }
    val missing = listings.size - located.size
    val notice = "Approximate public listing locations; dates and availability must be confirmed on Airbnb." +
        (if (missing > 0) " $missing listing(s) have no coordinates and remain in the cards below." else "") +
        (if (userPosition == null) " Your position is unavailable. Enable Device location in this chat and allow Android location access to see distances and routes." else " Numbered pins are sorted by straight-line distance from your current position.")
    return LocationMapData(center, places, notice, showOrigin = userPosition != null, title = "Airbnb")
}

@Composable
internal fun AirbnbListingResults(events: List<ToolEvent>, owner: String?, modifier: Modifier = Modifier, profilesByRun: Map<String, String> = emptyMap()) {
    val snapshot by produceState(Pair(emptyList<AirbnbListing>(), emptyList<String?>()), events, profilesByRun, owner) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val stored = airbnbListingResults(events)
            val identities = stored.map { listing ->
                toolResultOwner(events, profilesByRun, owner) { event ->
                    airbnbListingResults(listOf(event)).any { AirbnbListings.stayKey(it) == AirbnbListings.stayKey(listing) }
                }
            }
            stored to identities
        }
    }
    val storedListings = snapshot.first
    val owners = snapshot.second
    if (storedListings.isEmpty()) return
    val model: AirbnbListingViewModel = hiltViewModel(key = "airbnb-listings-$owner")
    val cachedDetails by model.cachedDetails.collectAsStateWithLifecycle()
    val listings = remember(storedListings, owners, cachedDetails) {
        storedListings.mapIndexed { index, listing ->
            cachedDetails[listOf(owners.getOrNull(index)) + AirbnbListings.stayKey(listing)]
                ?.let { AirbnbListings.merge(listing, it) } ?: listing
        }
    }
    var locationRetry by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationRetry++ }
    var locating by remember { mutableStateOf(false) }
    val position by produceState<MapCoordinate?>(null, owner, locationRetry) {
        locating = true
        try {
            value = model.currentPosition(owner ?: owners.firstOrNull { it != null })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = null
        } finally {
            locating = false
        }
    }
    val mapData = remember(listings, position) { airbnbMapData(listings, position) }
    var selected by remember(storedListings) { mutableStateOf<Int?>(null) }
    selected?.let { index ->
        AirbnbListingDialog(listings, index, owners.getOrNull(index), model, onSelect = { selected = it }, onDismiss = { selected = null })
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary)
            Text("Airbnb stays · ${listings.size}", style = MaterialTheme.typography.titleSmall)
        }
        if (locating) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (mapData != null) {
            EmbeddedLocationMap(mapData, onOpenPlace = { place -> selected = listings.indexOfFirst { it.id == place.id }.takeIf { it >= 0 } })
        } else {
            Text("These listings have no provider coordinates. Map pins will appear when coordinates are supplied; open a card to request listing details.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                locationRetry++
            }
        }, enabled = !locating) { Text("Refresh current position") }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(listings, key = { index, listing -> "${listing.id}-$index" }) { index, listing ->
                Card(
                    onClick = { selected = index },
                    modifier = Modifier.width(288.dp),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    ListingPhoto(listing.photos.firstOrNull(), owners.getOrNull(index), model, Modifier.fillMaxWidth().height(170.dp))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(listing.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        listing.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(listing.totalPrice ?: listing.price ?: "Price unavailable", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        listing.rating?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Star, null, Modifier.size(16.dp))
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        listing.nights?.let { Text("$it nights${listing.adults?.let { guests -> " · $guests adults" }.orEmpty()}", style = MaterialTheme.typography.labelSmall) }
                        if (listing.checkin != null && listing.checkout != null) Text("${listing.checkin} → ${listing.checkout}", style = MaterialTheme.typography.labelSmall)
                        val party = listOfNotNull(listing.children?.takeIf { it > 0 }?.let { "$it children" }, listing.infants?.takeIf { it > 0 }?.let { "$it infants" }, listing.pets?.takeIf { it > 0 }?.let { "$it pets" })
                        if (party.isNotEmpty()) Text(party.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        Text(if (listing.feeLines.isEmpty()) "Fee breakdown not supplied" else "${listing.feeLines.size} price and fee lines available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (listing.reviewAnalysis.sampleSize > 0) Text("${listing.reviewAnalysis.sampleSize} supplied reviews · ${listing.reviewAnalysis.redFlags.size} flags to review", style = MaterialTheme.typography.labelSmall, color = if (listing.reviewAnalysis.redFlags.isEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        if (listing.amenities.isNotEmpty()) Text(listing.amenities.take(3).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListingPhoto(url: String?, owner: String?, model: AirbnbListingViewModel, modifier: Modifier) {
    var retry by remember(url) { mutableIntStateOf(0) }
    var loading by remember(owner, url) { mutableStateOf(url != null) }
    val bytes by produceState<ByteArray?>(null, owner, url, retry) {
        value = null
        loading = url != null
        try {
            if (url != null) value = model.photo(owner, url)
        } finally {
            loading = false
        }
    }
    val photo by amazonBitmap(bytes, 1024)
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .25f))) {
        Box(contentAlignment = Alignment.Center) {
            val image = photo.image
            if (image != null) {
                Image(image, "Airbnb listing photo", Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Image, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (url == null) {
                            "Photo not supplied"
                        } else if (loading || photo.loading) {
                            "Loading photo…"
                        } else {
                            "Photo unavailable"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (loading || photo.loading) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    } else if (url != null) {
                        TextButton(onClick = { retry++ }) { Text("Retry photo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AirbnbListingDialog(listings: List<AirbnbListing>, index: Int, owner: String?, model: AirbnbListingViewModel, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val original = listings[index]
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(original, owner) { model.open(owner, original) }
    DisposableEffect(model) { onDispose { model.close() } }
    val listing = state.listing?.takeIf { AirbnbListings.stayKey(it) == AirbnbListings.stayKey(original) } ?: original
    var photoIndex by remember(listing.id) { mutableIntStateOf(0) }
    var openError by remember(listing.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    fun open(url: String) {
        openError = runCatching { uri.openUri(url) }.isFailure
    }
    FadingDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f))) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Airbnb · ${index + 1} / ${listings.size}", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { onSelect(index - 1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous listing") }
                    IconButton(onClick = { onSelect(index + 1) }, enabled = index < listings.lastIndex) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next listing") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(listing.title, style = MaterialTheme.typography.titleLarge)
                ListingPhoto(
                    listing.photos.getOrNull(photoIndex),
                    owner,
                    model,
                    Modifier.fillMaxWidth().height(230.dp).pointerInput(listing.id, photoIndex, listing.photos.size) {
                        var drag = 0f
                        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                            change.consume()
                            drag += amount
                        }, onDragEnd = {
                            if (drag < -60 && photoIndex < listing.photos.lastIndex) photoIndex++
                            if (drag > 60 && photoIndex > 0) photoIndex--
                        })
                    }
                )
                if (listing.photos.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { photoIndex-- }, enabled = photoIndex > 0) { Text("Previous photo") }
                        Text("${photoIndex + 1} / ${listing.photos.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { photoIndex++ }, enabled = photoIndex < listing.photos.lastIndex) { Text("Next photo") }
                    }
                }
                listing.location?.let {
                    Row {
                        Icon(Icons.Rounded.Place, null)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                listing.rating?.let { Text("★ $it", color = MaterialTheme.colorScheme.secondary) }
                Text(listing.totalPrice ?: listing.price ?: "Price not supplied", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (listing.checkin != null || listing.checkout != null) Text("${listing.checkin ?: "Check-in unknown"} → ${listing.checkout ?: "Check-out unknown"}", style = MaterialTheme.typography.bodySmall)
                (listing.priceLines + listing.feeLines).distinct().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(if (listing.feeLines.isEmpty()) "Cleaning, service fees and taxes have not been supplied. Confirm the final total on Airbnb." else "Provider price breakdown; verify the final total on Airbnb.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listing.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (listing.amenities.isNotEmpty()) {
                    Text("Amenities", style = MaterialTheme.typography.titleSmall)
                    Text(listing.amenities.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (listing.houseRules.isNotEmpty()) {
                    Text("House rules", style = MaterialTheme.typography.titleSmall)
                    Text(listing.houseRules.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                Text("Review signals", style = MaterialTheme.typography.titleSmall)
                Text(listing.reviewAnalysis.summary, style = MaterialTheme.typography.bodySmall)
                listing.reviewAnalysis.redFlags.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (openError) Text("Could not open a browser or maps app.", color = MaterialTheme.colorScheme.error)
                FilledTonalButton(onClick = { open(listing.url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.OpenInNew, null)
                    Text("Open on Airbnb", Modifier.padding(start = 8.dp))
                }
                if (listing.latitude != null && listing.longitude != null) TextButton(onClick = { open("https://www.google.com/maps/search/?api=1&query=${listing.latitude},${listing.longitude}") }) { Text("View area on map") }
                Row {
                    TextButton(onClick = { model.open(owner, original, refresh = true) }, enabled = !state.loading) { Text("Refresh details") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
