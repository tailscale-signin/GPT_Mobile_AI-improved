package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.agent.tool.MapCoordinate
import dev.chungjungsoo.gptmobile.data.agent.tool.NearbyPlace
import dev.chungjungsoo.gptmobile.data.agent.tool.NearbyPlacesClient
import dev.chungjungsoo.gptmobile.data.agent.tool.PlaceRoute
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Keep a complete pointer stream inside the embedded map, including two-finger gestures. */
internal class EmbeddedMapView(context: Context) : MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
}

@HiltViewModel
class LocationMapViewModel @Inject constructor(private val client: NearbyPlacesClient) : ViewModel() {
    suspend fun route(origin: MapCoordinate, place: NearbyPlace, walking: Boolean) = client.route(origin, MapCoordinate(place.latitude, place.longitude), walking)
}

internal data class LocationMapData(val origin: MapCoordinate, val places: List<NearbyPlace>, val status: String?)

internal fun locationMapData(events: List<ToolEvent>): LocationMapData? = events.asReversed().firstNotNullOfOrNull { event ->
    if (event.status != ToolEventStatus.COMPLETED || event.isError) return@firstNotNullOfOrNull null
    val identity = "${event.toolName} ${event.modelToolName}".lowercase(Locale.ROOT)
    if (listOf("location", "geo", "map", "place").none { it in identity }) return@firstNotNullOfOrNull null
    val raw = event.recoveryResult().orEmpty().take(100_000)
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val google = (obj?.get("data") as? JsonObject)?.takeIf { "google" in identity }
    val rawPlaces = (obj?.get("places") as? JsonArray) ?: (google?.get("places") as? JsonArray)
        ?: google?.takeIf { "location" in it }?.let { JsonArray(listOf(it)) }
    val request = runCatching { Json.parseToJsonElement(event.arguments) as? JsonObject }.getOrNull()
    val firstCoordinate = (rawPlaces?.firstOrNull() as? JsonObject)?.get("location") as? JsonObject
    fun number(vararg names: String): Double? = names.firstNotNullOfOrNull { name ->
        if (obj != null) {
            runCatching { obj[name]?.jsonPrimitive?.doubleOrNull }.getOrNull()
        } else {
            Regex("[\"']?$name[\"']?\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }

    val lat = number("latitude", "lat") ?: (request?.get("latitude") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
        ?: (firstCoordinate?.get("latitude") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: return@firstNotNullOfOrNull null
    val lon = number("longitude", "lon", "lng") ?: (request?.get("longitude") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
        ?: (firstCoordinate?.get("longitude") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: return@firstNotNullOfOrNull null
    val origin = MapCoordinate(lat, lon).takeIf { it.isValid } ?: return@firstNotNullOfOrNull null
    val places = rawPlaces.orEmpty().take(12).mapNotNull { item ->
        runCatching {
            val place = item as? JsonObject ?: return@runCatching null
            val location = place["location"] as? JsonObject ?: place
            val latitude = location["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val longitude = location["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val coordinate = MapCoordinate(latitude, longitude).takeIf { it.isValid } ?: return@runCatching null
            val displayName = place["displayName"] as? JsonObject
            val title = (displayName?.get("text") ?: place["name"])?.jsonPrimitive?.contentOrNull?.take(120) ?: return@runCatching null
            NearbyPlace(place["id"]?.jsonPrimitive?.contentOrNull ?: "$latitude,$longitude", title, latitude, longitude, dev.chungjungsoo.gptmobile.data.agent.tool.distanceMeters(origin, coordinate))
        }.getOrNull()
    }.distinctBy { it.id }.sortedBy { it.distanceMeters }
    LocationMapData(origin, places, runCatching { obj?.get("places_status")?.jsonPrimitive?.contentOrNull }.getOrNull())
}

@Composable
internal fun LocationToolMapPreview(toolEvents: List<ToolEvent>, modifier: Modifier = Modifier, viewModel: LocationMapViewModel? = null) {
    val data = remember(toolEvents) { locationMapData(toolEvents) } ?: return
    val mapViewModel = viewModel ?: hiltViewModel<LocationMapViewModel>()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val origin = data.origin
    var selectedId by rememberSaveable(origin) { mutableStateOf<String?>(null) }
    var walking by rememberSaveable { mutableStateOf(true) }
    var walkRoute by remember(origin, selectedId) { mutableStateOf<PlaceRoute?>(null) }
    var driveRoute by remember(origin, selectedId) { mutableStateOf<PlaceRoute?>(null) }
    var loading by remember(origin, selectedId) { mutableStateOf(false) }
    var routeError by remember(origin, selectedId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val selected = data.places.firstOrNull { it.id == selectedId }
    val disposed = remember(context, owner, origin) { AtomicBoolean(false) }
    var map by remember(context, owner, origin) { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember(context, owner, origin) { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val secondary = MaterialTheme.colorScheme.secondary.toArgb()
    val mapView = remember(context, owner, origin) {
        org.maplibre.android.MapLibre.getInstance(context)
        EmbeddedMapView(context).apply {
            onCreate(null)
            getMapAsync { active ->
                if (!disposed.get()) {
                    active.cameraPosition = CameraPosition.Builder().target(LatLng(origin.latitude, origin.longitude)).zoom(15.0).build()
                    active.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")) { style ->
                        if (!disposed.get()) {
                            style.addSource(GeoJsonSource("location-origin", Point.fromLngLat(origin.longitude, origin.latitude)))
                            style.addSource(GeoJsonSource("location-places", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                            style.addSource(GeoJsonSource("location-route", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                            style.addLayer(LineLayer("location-route-line", "location-route").withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(5f)))
                            style.addLayer(CircleLayer("location-places-pins", "location-places").withProperties(PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(secondary), PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor("#FFFFFF")))
                            style.addLayer(CircleLayer("location-origin-pin", "location-origin").withProperties(PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(accent), PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor("#FFFFFF")))
                            active.addOnMapClickListener { point ->
                                val hit = active.queryRenderedFeatures(active.projection.toScreenLocation(point), "location-places-pins").firstOrNull()
                                hit?.getStringProperty("placeId")?.let { selectedId = it }
                                hit != null
                            }
                            map = active
                            ready = true
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(mapView, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> mapView.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            disposed.set(true)
            owner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(ready, data.places, accent, secondary) {
        val active = map ?: return@LaunchedEffect
        val style = active.style ?: return@LaunchedEffect
        val features = data.places.map { place ->
            Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply { addStringProperty("placeId", place.id) }
        }
        style.getSourceAs<GeoJsonSource>("location-places")?.setGeoJson(FeatureCollection.fromFeatures(features))
        style.getLayerAs<CircleLayer>("location-origin-pin")?.setProperties(PropertyFactory.circleColor(accent))
        style.getLayerAs<CircleLayer>("location-places-pins")?.setProperties(PropertyFactory.circleColor(secondary))
        style.getLayerAs<LineLayer>("location-route-line")?.setProperties(PropertyFactory.lineColor(accent))
    }
    // Fit only when the location results change; theme and route updates preserve the user's camera.
    LaunchedEffect(ready, origin, data.places.map { it.id }) {
        val active = map ?: return@LaunchedEffect
        if (data.places.isNotEmpty()) {
            val bounds = LatLngBounds.Builder().include(LatLng(origin.latitude, origin.longitude))
            data.places.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
            active.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 45))
        }
    }
    LaunchedEffect(origin, selected, retry) {
        if (selected == null) return@LaunchedEffect
        loading = true
        routeError = null
        try {
            suspend fun load(walk: Boolean): PlaceRoute? = try {
                mapViewModel.route(origin, selected, walk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                routeError = "Some routes are unavailable. Retry or open your maps app."
                null
            }
            walkRoute = load(true)
            driveRoute = load(false)
        } finally {
            loading = false
        }
    }
    val route = if (walking) walkRoute else driveRoute
    LaunchedEffect(ready, route) {
        val source = map?.style?.getSourceAs<GeoJsonSource>("location-route") ?: return@LaunchedEffect
        val features = route?.let { listOf(Feature.fromGeometry(LineString.fromLngLats(it.coordinates.map { p -> Point.fromLngLat(p.longitude, p.latitude) }))) }.orEmpty()
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
        Column {
            androidx.compose.runtime.key(mapView) { AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(250.dp)) }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Location${if (data.places.isNotEmpty()) " · ${data.places.size} nearby places" else ""}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(origin.latitude, origin.longitude), 15.0)) }) {
                        Icon(Icons.Rounded.MyLocation, "Recenter map", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                data.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                data.places.forEachIndexed { index, place ->
                    TextButton(onClick = { selectedId = place.id }) {
                        Text("${index + 1}. ${place.name} · ${formatMapDistance(place.distanceMeters)} away", color = if (selectedId == place.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                selected?.let { place ->
                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                    Text("${formatMapDistance(place.distanceMeters)} straight-line distance", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = walking, onClick = { walking = true }, label = { Text("Walk") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null) })
                        FilterChip(selected = !walking, onClick = { walking = false }, label = { Text("Drive") }, leadingIcon = { Icon(Icons.Rounded.DirectionsCar, null) })
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Walk: ${formatMapRoute(walkRoute)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Drive: ${formatMapRoute(driveRoute)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Estimated travel times; traffic and current closures are not included.", style = MaterialTheme.typography.bodySmall)
                    routeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (routeError != null) TextButton(onClick = { retry++ }, enabled = !loading) { Text("Retry routes") }
                    TextButton(onClick = { open("https://www.google.com/maps/dir/?api=1&origin=${origin.latitude},${origin.longitude}&destination=${place.latitude},${place.longitude}&travelmode=${if (walking) "walking" else "driving"}") }) { Text("Open directions") }
                }
                Text("© OpenStreetMap contributors · OpenFreeMap · Routes: FOSSGIS / OSRM", style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { open("https://www.openstreetmap.org/?mlat=${origin.latitude}&mlon=${origin.longitude}#map=16/${origin.latitude}/${origin.longitude}") }) { Text("Open / fix the map") }
                }
            }
        }
    }
}

private fun formatMapDistance(meters: Double): String = if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(Locale.getDefault(), meters / 1000)
private fun formatMapRoute(route: PlaceRoute?): String = route?.let { "${formatMapDistance(it.distanceMeters)} · ~${kotlin.math.ceil(it.durationSeconds / 60).toInt().coerceAtLeast(1)} min" } ?: "Not available yet"
