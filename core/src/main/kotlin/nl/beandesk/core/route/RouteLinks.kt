package nl.beandesk.core.route

import nl.beandesk.core.model.LatLng

enum class TravelMode(internal val parameter: String) {
    BICYCLING("bicycling"),
    WALKING("walking"),
}

object RouteLinks {
    /**
     * A Google Maps URL that opens the route overview (not turn-by-turn navigation).
     * Works in the Google Maps app and falls back to the browser without it.
     */
    fun googleMaps(destination: LatLng, mode: TravelMode = TravelMode.BICYCLING): String =
        "https://www.google.com/maps/dir/?api=1" +
            "&destination=${destination.lat},${destination.lng}" +
            "&travelmode=${mode.parameter}"
}
