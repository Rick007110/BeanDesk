package nl.beandesk.core.geo

import nl.beandesk.core.model.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

class DistanceTest {
    @Test
    fun `distance between Utrecht Centraal and the Dom tower`() {
        val centraal = LatLng(52.0894, 5.1101)
        val dom = LatLng(52.0907, 5.1214)
        assertEquals(785.0, distanceMeters(centraal, dom), 10.0)
    }

    @Test
    fun `same point is zero`() {
        val point = LatLng(52.0907, 5.1214)
        assertEquals(0.0, distanceMeters(point, point), 0.001)
    }
}
