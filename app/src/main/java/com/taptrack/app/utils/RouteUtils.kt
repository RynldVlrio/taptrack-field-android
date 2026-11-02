package com.taptrack.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL

data class RouteResult(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

suspend fun fetchRoute(
    fromLat: Double, fromLng: Double,
    toLat: Double, toLng: Double,
    userAgent: String
): RouteResult? = withContext(Dispatchers.IO) {
    runCatching {
        val url = "https://router.project-osrm.org/route/v1/driving/$fromLng,$fromLat;$toLng,$toLat?overview=full&geometries=polyline"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        if (conn.responseCode != 200) return@runCatching null
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val routes = json.getJSONArray("routes")
        if (routes.length() == 0) return@runCatching null
        val route = routes.getJSONObject(0)
        RouteResult(
            points = decodePolyline(route.getString("geometry")),
            distanceMeters = route.getDouble("distance"),
            durationSeconds = route.getDouble("duration")
        )
    }.getOrNull()
}

private fun decodePolyline(encoded: String): List<GeoPoint> {
    val poly = mutableListOf<GeoPoint>()
    var index = 0; val len = encoded.length
    var lat = 0; var lng = 0
    while (index < len) {
        var b: Int; var shift = 0; var result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0; result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        poly.add(GeoPoint(lat / 1e5, lng / 1e5))
    }
    return poly
}

fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else "${"%.1f".format(meters / 1000)} km"

fun formatDuration(seconds: Double): String {
    val min = (seconds / 60).toInt()
    return if (min < 60) "$min min" else "${min / 60}h ${min % 60}min"
}
