package com.durga.emergency.utils

import kotlin.math.*

object GeoUtils {

    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculates distance between two coordinates in kilometers using Haversine formula.
     */
    fun calculateHaversineDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val originLatRad = Math.toRadians(lat1)
        val targetLatRad = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) +
                sin(dLon / 2).pow(2) *
                cos(originLatRad) * cos(targetLatRad)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_KM * c
    }
}
