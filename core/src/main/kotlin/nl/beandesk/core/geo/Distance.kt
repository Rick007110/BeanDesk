package nl.beandesk.core.geo

import nl.beandesk.core.model.LatLng
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Great-circle distance (haversine); accurate to well within a metre at city scale. */
fun distanceMeters(from: LatLng, to: LatLng): Double {
    val lat1 = from.lat.toRadians()
    val lat2 = to.lat.toRadians()
    val dLat = lat2 - lat1
    val dLng = (to.lng - from.lng).toRadians()
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

private fun Double.toRadians(): Double = this * PI / 180
