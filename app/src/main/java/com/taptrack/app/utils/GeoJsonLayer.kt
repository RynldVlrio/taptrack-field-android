package com.taptrack.app.utils

import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Reads a shared map layer (GeoJSON written by the web app) into the shapes the map draws.
 * Polygons use their outer ring. Each feature may carry "name" (label) and "color" (#rrggbb, set on the web).
 */
object GeoJsonLayer {
    fun read(file: File, layerName: String): ParsedBoundary {
        val root = JSONObject(file.readText())
        val polygons = mutableListOf<NamedFeature>()
        val lines = mutableListOf<NamedFeature>()
        val points = mutableListOf<NamedFeature>()
        val features: JSONArray = when (root.optString("type")) {
            "FeatureCollection" -> root.optJSONArray("features") ?: JSONArray()
            "Feature" -> JSONArray().put(root)
            else -> JSONArray().put(JSONObject().put("type", "Feature").put("geometry", root))
        }
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties")
            val name = props?.optString("name", "") ?: ""
            val color = props?.optString("color", "")?.let { parseColor(it) }
            addGeometry(f.optJSONObject("geometry") ?: continue, name, color, polygons, lines, points)
        }
        val legend = root.optJSONArray("legend")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = parseColor(o.optString("color")) ?: return@mapNotNull null
                LegendRow(c, o.optString("label", ""), o.optString("kind", "line"))
            }
        } ?: emptyList()
        val title = root.optString("legendTitle", "").takeIf { it.isNotBlank() && it != "null" }
        return ParsedBoundary(layerName, polygons, lines, points, legend, title)
    }

    private fun parseColor(s: String): Int? =
        if (Regex("^#[0-9a-fA-F]{6}$").matches(s)) (0xFF000000.toInt() or s.substring(1).toInt(16)) else null

    private fun addGeometry(
        g: JSONObject, name: String, color: Int?,
        polygons: MutableList<NamedFeature>, lines: MutableList<NamedFeature>, points: MutableList<NamedFeature>,
    ) {
        val c = g.optJSONArray("coordinates")
        when (g.optString("type")) {
            "Point" -> c?.let { pt(it) }?.let { points += NamedFeature(name, listOf(listOf(it)), color) }
            "MultiPoint" -> c?.let { mp -> for (i in 0 until mp.length()) pt(mp.optJSONArray(i))?.let { points += NamedFeature(name, listOf(listOf(it)), color) } }
            "Polygon" -> c?.let { ring(it.optJSONArray(0)) }?.let { polygons += NamedFeature(name, listOf(it), color) }
            "MultiPolygon" -> c?.let { mp ->
                val rings = (0 until mp.length()).mapNotNull { ring(mp.optJSONArray(it)?.optJSONArray(0)) }
                if (rings.isNotEmpty()) polygons += NamedFeature(name, rings, color)
            }
            "LineString" -> c?.let { ring(it) }?.let { lines += NamedFeature(name, listOf(it), color) }
            "MultiLineString" -> c?.let { ml ->
                val parts = (0 until ml.length()).mapNotNull { ring(ml.optJSONArray(it)) }
                if (parts.isNotEmpty()) lines += NamedFeature(name, parts, color)
            }
            "GeometryCollection" -> g.optJSONArray("geometries")?.let { gs ->
                for (i in 0 until gs.length()) gs.optJSONObject(i)?.let { addGeometry(it, name, color, polygons, lines, points) }
            }
        }
    }

    private fun pt(p: JSONArray?): GeoPoint? = if (p == null || p.length() < 2) null else GeoPoint(p.optDouble(1), p.optDouble(0))

    /** [[lng,lat],…] → points (needs at least 2). */
    private fun ring(a: JSONArray?): List<GeoPoint>? {
        if (a == null) return null
        val pts = (0 until a.length()).mapNotNull { i -> pt(a.optJSONArray(i)) }
        return pts.takeIf { it.size >= 2 }
    }
}
