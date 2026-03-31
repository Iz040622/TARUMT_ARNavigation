package com.example.tarumtar.navigation

import com.google.android.gms.maps.model.LatLng
import kotlin.math.cos
import kotlin.math.sin

data class LocalCoordinate(
    val x: Double,
    val y: Double
)

class coordinatesHelper(
    private var center: LatLng
) {
    fun setCenter(newCenter: LatLng) {
        center = newCenter
    }

    fun getLocalCoordinates(point: LatLng, yawDegrees: Double): LocalCoordinate {
        val latitudeRadians = Math.toRadians(center.latitude)

        val metersPerDegreeLatitude =
            111132.92 -
                    559.82 * cos(2 * latitudeRadians) +
                    1.175 * cos(4 * latitudeRadians) -
                    0.0023 * cos(6 * latitudeRadians)

        val metersPerDegreeLongitude =
            111412.84 * cos(latitudeRadians) -
                    93.5 * cos(3 * latitudeRadians) +
                    0.118 * cos(5 * latitudeRadians)

        val dx = (point.longitude - center.longitude) * metersPerDegreeLongitude
        val dy = (point.latitude - center.latitude) * metersPerDegreeLatitude

        val radians = Math.toRadians(yawDegrees)

        val rotatedX = dx * cos(radians) - dy * sin(radians)
        val rotatedY = dx * sin(radians) + dy * cos(radians)

        return LocalCoordinate(rotatedX, rotatedY)
    }
}