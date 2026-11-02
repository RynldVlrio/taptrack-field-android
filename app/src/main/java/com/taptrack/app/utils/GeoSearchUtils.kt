package com.taptrack.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double
)

// Detects "lat, lon" or "lat lon" input and returns a GeoPoint if valid
fun parseCoordinates(query: String): GeoPoint? {
    val match = Regex("""^(-?\d{1,3}\.?\d*)[,\s]+(-?\d{1,3}\.?\d*)$""")
        .find(query.trim()) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lon = match.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return GeoPoint(lat, lon)
}

suspend fun geocodeSearch(query: String, userAgent: String): List<SearchResult> =
    withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            // countrycodes=ph keeps results in the Philippines; addressdetails=1 gives
            // structured address fields for accurate title/subtitle formatting
            val conn = URL(
                "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5" +
                "&addressdetails=1&countrycodes=ph"
            ).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return@runCatching emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val display = obj.getString("display_name")
                val addr = obj.optJSONObject("address")

                // Pick the most specific name available
                val title = addr?.let { a ->
                    a.optString("village").takeIf { it.isNotEmpty() }
                        ?: a.optString("hamlet").takeIf { it.isNotEmpty() }
                        ?: a.optString("suburb").takeIf { it.isNotEmpty() }
                        ?: a.optString("neighbourhood").takeIf { it.isNotEmpty() }
                        ?: a.optString("town").takeIf { it.isNotEmpty() }
                        ?: a.optString("city").takeIf { it.isNotEmpty() }
                        ?: a.optString("municipality").takeIf { it.isNotEmpty() }
                        ?: a.optString("county").takeIf { it.isNotEmpty() }
                } ?: display.substringBefore(",").trim()

                // Subtitle: municipality/city › province › region (skip parts equal to title)
                val subtitle = addr?.let { a ->
                    listOfNotNull(
                        a.optString("municipality").takeIf { it.isNotEmpty() && it != title },
                        a.optString("city").takeIf { it.isNotEmpty() && it != title },
                        a.optString("province").takeIf { it.isNotEmpty() },
                        a.optString("region").takeIf { it.isNotEmpty() }
                    ).take(3).joinToString(", ")
                }.takeIf { !it.isNullOrBlank() } ?: display.substringAfter(",").trim()

                SearchResult(
                    title = title,
                    subtitle = subtitle,
                    lat = obj.getString("lat").toDouble(),
                    lon = obj.getString("lon").toDouble()
                )
            }
        }.getOrDefault(emptyList())
    }
