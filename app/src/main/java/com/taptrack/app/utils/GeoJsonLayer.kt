package com.taptrack.app.utils

import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Reads a shared map layer (GeoJSON written by the web app) into the shapes the map draws.
 * Polygons use their outer ring; points are skipped (layers are boundaries / lines).
 */
object GeoJsonLayer {
    fun read(file: File, layerName: String): ParsedBoundary {
        val root = JSONObject(file.readText())
        val polygons = mutableListOf<NamedFeature>()
        val lines = mutableListOf<NamedFeature>()
        val features: JSONArray = when (root.optString("type")) {
            "FeatureCollection" -> root.optJSONArray("features") ?: JSONArray()
            "Feature" -> JSONArray().put(root)
            else -> JSONArray().put(JSONObject().put("type", "Feature").put("geometry", root))
        }
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val name = f.optJSONObject("properties")?.optString("name", "") ?: ""
            addGeometry(f.optJSONObject("geometry") ?: continue, name, polygons, lines)
        }
        return ParsedBoundary(layerName, polygons, lines)
    }

    private fun addGeometry(g: JSONObject, name: String, polygons: MutableList<NamedFeature>, lines: MutableList<NamedFeature>) {
        val c = g.optJSONArray("coordinates")
        when (g.optString("type")) {
            "Polygon" -> c?.let { ring(it.optJSONArray(0)) }?.let { polygons += NamedFeature(name, listOf(it)) }
            "MultiPolygon" -> c?.let { mp ->
                val rings = (0 until mp.length()).mapNotNull { ring(mp.optJSONArray(it)?.optJSONArray(0)) }
                if (rings.isNotEmpty()) polygons += NamedFeature(name, rings)
            }
            "LineString" -> c?.let { ring(it) }?.let { lines += NamedFeature(name, listOf(it)) }
            "MultiLineString" -> c?.let { ml ->
                val parts = (0 until ml.length()).mapNotNull { ring(ml.optJSONArray(it)) }
                if (parts.isNotEmpty()) lines += NamedFeature(name, parts)
            }
            "GeometryCollection" -> g.optJSONArray("geometries")?.let { gs ->
                for (i in 0 until gs.length()) gs.optJSONObject(i)?.let { addGeometry(it, name, polygons, lines) }
            }
        }
    }

    /** [[lng,lat],…] → points (needs at least 2). */
    private fun ring(a: JSONArray?): List<GeoPoint>? {
        if (a == null) return null
        val pts = (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONArray(i) ?: return@mapNotNull null
            if (p.length() < 2) null else GeoPoint(p.optDouble(1), p.optDouble(0))
        }
        return pts.takeIf { it.size >= 2 }
    }
}
