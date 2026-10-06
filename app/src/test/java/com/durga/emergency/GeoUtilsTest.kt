package com.durga.emergency

import com.durga.emergency.utils.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun calculateDistance_identicalCoordinates_returnsZero() {
        val distance = GeoUtils.calculateHaversineDistanceKm(18.5204, 73.8567, 18.5204, 73.8567)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun calculateDistance_knownPoints_returnsAccurateDistance() {
        // Distance between Pune (18.5204, 73.8567) and Mumbai (19.0760, 72.8777) is approx ~118-120 km
        val distance = GeoUtils.calculateHaversineDistanceKm(18.5204, 73.8567, 19.0760, 72.8777)
        assertTrue(distance in 115.0..125.0)
    }

    @Test
    fun calculateDistance_shortLocalDistance_returnsAccurateKm() {
        // Approx 1 km northward shift (~0.009 degrees latitude)
        val distance = GeoUtils.calculateHaversineDistanceKm(18.5200, 73.8500, 18.5290, 73.8500)
        assertEquals(1.0, distance, 0.05)
    }
}
