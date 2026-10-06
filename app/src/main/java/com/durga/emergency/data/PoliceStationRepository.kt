package com.durga.emergency.data

import android.content.Context
import com.durga.emergency.data.model.PoliceStation
import com.durga.emergency.utils.GeoUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

interface PoliceStationRepository {
    fun getAllStations(): List<PoliceStation>
    fun findNearestStation(latitude: Double, longitude: Double): Pair<PoliceStation?, Double?>
}

class LocalPoliceStationRepository(private val context: Context) : PoliceStationRepository {

    private val cachedStations: List<PoliceStation> by lazy {
        loadStationsFromAsset()
    }

    private fun loadStationsFromAsset(): List<PoliceStation> {
        return try {
            val inputStream = context.assets.open("police_stations.json")
            val reader = InputStreamReader(inputStream)
            val listType = object : TypeToken<List<PoliceStation>>() {}.type
            Gson().fromJson(reader, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getAllStations(): List<PoliceStation> = cachedStations

    override fun findNearestStation(latitude: Double, longitude: Double): Pair<PoliceStation?, Double?> {
        val stations = getAllStations()
        if (stations.isEmpty()) return Pair(null, null)

        var nearest: PoliceStation? = null
        var minDistance = Double.MAX_VALUE

        for (station in stations) {
            val dist = GeoUtils.calculateHaversineDistanceKm(
                latitude, longitude,
                station.latitude, station.longitude
            )
            if (dist < minDistance) {
                minDistance = dist
                nearest = station
            }
        }

        return Pair(nearest, if (nearest != null) minDistance else null)
    }
}
