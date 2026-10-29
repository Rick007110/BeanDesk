package nl.beandesk.core.route

import nl.beandesk.core.model.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteLinksTest {
    @Test
    fun `cycling route is the default`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=52.0907,5.1214&travelmode=bicycling",
            RouteLinks.googleMaps(LatLng(52.0907, 5.1214)),
        )
    }

    @Test
    fun `walking route`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=52.0907,5.1214&travelmode=walking",
            RouteLinks.googleMaps(LatLng(52.0907, 5.1214), TravelMode.WALKING),
        )
    }
}
