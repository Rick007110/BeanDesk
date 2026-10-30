package nl.beandesk.app.ui.places

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import nl.beandesk.core.model.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLatLng

private const val SOURCE_ID = "places"
private const val LAYER_ID = "places-circles"
private const val RATING_LAYER_ID = "places-ratings"
private const val RATING = "rating"
private const val RATING_IMAGE_PREFIX = "rating-"
private const val USER_SOURCE_ID = "user-location"
private const val USER_LAYER_ID = "user-location-dot"
private const val PLACE_ID = "id"
private const val CITY_ZOOM = 13.0
private const val PLACE_ZOOM = 15.5

/**
 * MapLibre map with one circle per place and a blue dot for the user. The camera starts on
 * [center], again whenever [areaKey] changes (a search), moves to [focus] whenever that
 * changes, and to [userLocation] each time [recenterRequests] goes up.
 */
@Composable
fun PlaceMap(
    items: List<PlaceItem>,
    areaKey: String,
    center: LatLng?,
    focus: LatLng?,
    userLocation: LatLng?,
    recenterRequests: Int,
    styleUrl: String,
    onPlaceClick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val currentOnPlaceClick by rememberUpdatedState(onPlaceClick)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // Per area: a new search moves the camera to what was found.
    var centered by remember(areaKey) { mutableStateOf(false) }

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
                // "★ 4,3" next to rated places; the label images are added per value (see below).
                loadedStyle.addLayer(
                    SymbolLayer(RATING_LAYER_ID, SOURCE_ID)
                        .withFilter(has(RATING))
                        .withProperties(
                            iconImage(concat(literal(RATING_IMAGE_PREFIX), get(RATING))),
                            iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT),
                            iconOffset(arrayOf(4f, -4f)),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true),
                        ),
                )
                // Added last, so the user's dot is drawn on top of the places.
                loadedStyle.addSource(GeoJsonSource(USER_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
                loadedStyle.addLayer(
                    CircleLayer(USER_LAYER_ID, USER_SOURCE_ID).withProperties(
                        circleRadius(8f),
                        circleColor("#1A73E8"),
                        circleStrokeWidth(3f),
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

    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(style, items, locale) {
        val loadedStyle = style ?: return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        val features = items.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.place.location.lng, item.place.location.lat)).apply {
                addNumberProperty(PLACE_ID, item.place.id)
                item.place.rating?.let { rating ->
                    val label = rating.averageText(locale)
                    if (loadedStyle.getImage(RATING_IMAGE_PREFIX + label) == null) {
                        loadedStyle.addImage(RATING_IMAGE_PREFIX + label, ratingLabel(label, density))
                    }
                    addStringProperty(RATING, label)
                }
            }
        }
        loadedStyle.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, userLocation) {
        val features = listOfNotNull(userLocation?.let { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) })
        style?.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // The "my location" button; the first composition (0) does not move the camera.
    LaunchedEffect(map, recenterRequests) {
        val loadedMap = map ?: return@LaunchedEffect
        val here = userLocation ?: return@LaunchedEffect
        if (recenterRequests > 0) {
            loadedMap.animateCamera(CameraUpdateFactory.newLatLngZoom(MapLatLng(here.lat, here.lng), PLACE_ZOOM))
            centered = true
        }
    }

    // A place to focus on wins; otherwise the camera jumps to the area once,
    // after which the user is in control.
    LaunchedEffect(map, areaKey, center, focus) {
        val loadedMap = map ?: return@LaunchedEffect
        if (focus != null) {
            loadedMap.animateCamera(CameraUpdateFactory.newLatLngZoom(MapLatLng(focus.lat, focus.lng), PLACE_ZOOM))
            centered = true
        } else if (center != null && !centered) {
            loadedMap.cameraPosition = CameraPosition.Builder()
                .target(MapLatLng(center.lat, center.lng))
                .zoom(CITY_ZOOM)
                .build()
            centered = true
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** A small white pill with an amber star and the average, e.g. "★ 4,3", drawn once per value. */
private fun ratingLabel(text: String, density: Float): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF3B2A20.toInt()
    }
    val starPaint = Paint(textPaint).apply { color = StarColor.toArgb() }
    val star = "\u2605 "
    val padding = 6f * density
    val width = starPaint.measureText(star) + textPaint.measureText(text) + 2 * padding
    val height = textPaint.fontSpacing + padding
    val bitmap = Bitmap.createBitmap(width.toInt() + 1, height.toInt() + 1, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val pill = RectF(density / 2, density / 2, width - density / 2, height - density / 2)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x33000000
    }
    canvas.drawRoundRect(pill, height / 2, height / 2, fill)
    canvas.drawRoundRect(pill, height / 2, height / 2, border)
    val baseline = height / 2 - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(star, padding, baseline, starPaint)
    canvas.drawText(text, padding + starPaint.measureText(star), baseline, textPaint)
    return bitmap
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
