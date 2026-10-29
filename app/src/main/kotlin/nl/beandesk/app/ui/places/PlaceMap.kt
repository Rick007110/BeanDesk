package nl.beandesk.app.ui.places

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import nl.beandesk.core.model.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLatLng

private const val SOURCE_ID = "places"
private const val LAYER_ID = "places-circles"
private const val PLACE_ID = "id"
private const val CITY_ZOOM = 13.0

/**
 * MapLibre map with one circle per place. TODO for the MVP: the user's own location
 * (blue dot), which needs the location permission flow from the plan.
 */
@Composable
fun PlaceMap(
    items: List<PlaceItem>,
    center: LatLng?,
    styleUrl: String,
    onPlaceClick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val currentOnPlaceClick by rememberUpdatedState(onPlaceClick)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centered by remember { mutableStateOf(false) }

    MapLifecycle(mapView)

    LaunchedEffect(mapView, styleUrl) {
        mapView.getMapAsync { loadedMap ->
            loadedMap.setStyle(styleUrl) { loadedStyle ->
                loadedStyle.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
                loadedStyle.addLayer(
                    CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
                        circleRadius(7f),
                        circleColor("#6F4E37"),
                        circleStrokeWidth(2f),
                        circleStrokeColor("#FFFFFF"),
                    ),
                )
                style = loadedStyle
            }
            loadedMap.addOnMapClickListener { point ->
                val screenPoint = loadedMap.projection.toScreenLocation(point)
                val feature = loadedMap.queryRenderedFeatures(screenPoint, LAYER_ID).firstOrNull()
                currentOnPlaceClick(feature?.getNumberProperty(PLACE_ID)?.toLong())
                feature != null
            }
            map = loadedMap
        }
    }

    LaunchedEffect(style, items) {
        val features = items.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.place.location.lng, item.place.location.lat)).apply {
                addNumberProperty(PLACE_ID, item.place.id)
            }
        }
        style?.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Moves the camera once, when the city is known; after that the user is in control.
    LaunchedEffect(map, center) {
        val target = center ?: return@LaunchedEffect
        val loadedMap = map ?: return@LaunchedEffect
        if (!centered) {
            loadedMap.cameraPosition = CameraPosition.Builder()
                .target(MapLatLng(target.lat, target.lng))
                .zoom(CITY_ZOOM)
                .build()
            centered = true
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Forwards the screen's lifecycle to the MapView, as MapLibre requires. */
@Composable
private fun MapLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                Lifecycle.Event.ON_ANY -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
