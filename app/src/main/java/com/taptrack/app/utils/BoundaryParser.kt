package com.taptrack.app.utils

import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * One geographic feature with an optional label name and one or more rings/parts.
 * For polygons each ring is a closed boundary; for polylines each part is a segment.
 */
data class NamedFeature(
    val name: String,
    val rings: List<List<GeoPoint>>
)

data class ParsedBoundary(
    val name: String,
    val polygons: List<NamedFeature>,
    val polylines: List<NamedFeature>
)

object BoundaryParser {

    /** Parse a ZIP archive containing ESRI shapefile components (.shp + optional .dbf). */
    fun parseShapefileZip(inputStream: InputStream): ParsedBoundary {
        var shpBytes: ByteArray? = null
        var dbfBytes: ByteArray? = null
        var layerName = "Shapefile"

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name.substringAfterLast('/')
                when {
                    entryName.endsWith(".shp", ignoreCase = true) -> {
                        shpBytes = zip.readBytes()
                        layerName = entryName.substringBeforeLast('.')
                    }
                    entryName.endsWith(".dbf", ignoreCase = true) -> {
                        dbfBytes = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val data = shpBytes ?: return ParsedBoundary(layerName, emptyList(), emptyList())
        return try {
            val records = ShapefileReader.readShp(data.inputStream())
            val featureNames = dbfBytes?.let { readDbfNames(it) } ?: emptyList()
            buildFromRecords(layerName, records, featureNames)
        } catch (_: Exception) {
            ParsedBoundary(layerName, emptyList(), emptyList())
        }
    }

    /** Parse a raw .shp file directly (no ZIP, no DBF — features will have no individual names). */
    fun parseShpFile(inputStream: InputStream, name: String): ParsedBoundary {
        return try {
            val records = ShapefileReader.readShp(inputStream)
            buildFromRecords(name, records, emptyList())
        } catch (_: Exception) {
            ParsedBoundary(name, emptyList(), emptyList())
        }
    }

    /** Parse a KMZ file (ZIP containing a .kml). */
    fun parseKmz(inputStream: InputStream): ParsedBoundary {
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".kml", ignoreCase = true)) {
                    val bytes = zip.readBytes()
                    return parseKml(bytes.inputStream())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ParsedBoundary("", emptyList(), emptyList())
    }

    /** Parse a KML file; each Placemark's <name> becomes the feature label. */
    fun parseKml(inputStream: InputStream): ParsedBoundary {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        val polygons  = mutableListOf<NamedFeature>()
        val polylines = mutableListOf<NamedFeature>()
        var docName = ""

        var inPlacemark    = false
        var placemarkName  = ""
        var inPolygon      = false
        var inOuterBound   = false
        var inLineString   = false
        var readingCoords  = false
        val coordsBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfterLast(':')
                    when (tag) {
                        "name" -> {
                            val t = parser.nextText().trim()
                            if (inPlacemark) {
                                if (placemarkName.isEmpty()) placemarkName = t
                            } else if (docName.isEmpty() && t.isNotEmpty()) {
                                docName = t
                            }
                        }
                        "Placemark"      -> { inPlacemark = true; placemarkName = "" }
                        "Polygon"        -> inPolygon = true
                        "outerBoundaryIs" -> inOuterBound = true
                        "LineString"     -> inLineString = true
                        "coordinates"    -> { readingCoords = true; coordsBuf.clear() }
                    }
                }
                XmlPullParser.TEXT -> if (readingCoords) coordsBuf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfterLast(':')
                    when (tag) {
                        "coordinates" -> {
                            if (readingCoords) {
                                val pts = parseKmlCoords(coordsBuf.toString())
                                when {
                                    inPolygon && inOuterBound && pts.size >= 3 ->
                                        polygons.add(NamedFeature(placemarkName, listOf(pts)))
                                    inLineString && pts.size >= 2 ->
                                        polylines.add(NamedFeature(placemarkName, listOf(pts)))
                                }
                                readingCoords = false
                            }
                        }
                        "Placemark"       -> { inPlacemark = false; inPolygon = false; inLineString = false }
                        "Polygon"         -> inPolygon = false
                        "outerBoundaryIs" -> inOuterBound = false
                        "LineString"      -> inLineString = false
                    }
                }
            }
            event = parser.next()
        }
        return ParsedBoundary(docName, polygons, polylines)
    }

    /** Parse a GPX file; each track/route <name> becomes the feature label. */
    fun parseGpx(inputStream: InputStream): ParsedBoundary {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        val polylines = mutableListOf<NamedFeature>()
        var docName = "GPX Track"

        // Track/route accumulation
        var inTrack    = false
        var inRoute    = false
        var trackName  = ""
        val trackParts = mutableListOf<List<GeoPoint>>()
        var segPts     = mutableListOf<GeoPoint>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfterLast(':')
                    when (tag) {
                        "name" -> {
                            val t = parser.nextText().trim()
                            when {
                                inTrack || inRoute -> if (trackName.isEmpty()) trackName = t
                                docName == "GPX Track" && t.isNotEmpty() -> docName = t
                            }
                        }
                        "trk"  -> { inTrack = true;  trackName = ""; trackParts.clear() }
                        "rte"  -> { inRoute = true;  trackName = ""; trackParts.clear() }
                        "trkseg" -> segPts = mutableListOf()
                        "trkpt", "rtept" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) segPts.add(GeoPoint(lat, lon))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfterLast(':')
                    when (tag) {
                        "trkseg" -> { if (segPts.size >= 2) trackParts.add(segPts.toList()) }
                        "rte"    -> { if (segPts.size >= 2) trackParts.add(segPts.toList()) }
                        "trk"    -> {
                            if (trackParts.isNotEmpty())
                                polylines.add(NamedFeature(trackName, trackParts.toList()))
                            inTrack = false
                        }
                        "rtept"  -> { /* handled via segPts */ }
                        // end of route (rte with per-rtept points)
                    }
                    if (tag == "rte" && inRoute) {
                        val allPts = trackParts.flatten()
                        if (allPts.size >= 2)
                            polylines.add(NamedFeature(trackName, listOf(allPts)))
                        inRoute = false
                    }
                }
            }
            event = parser.next()
        }
        return ParsedBoundary(docName, emptyList(), polylines)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildFromRecords(
        layerName: String,
        records: List<ShapeRecord>,
        featureNames: List<String>
    ): ParsedBoundary {
        val polygons  = mutableListOf<NamedFeature>()
        val polylines = mutableListOf<NamedFeature>()

        records.forEachIndexed { idx, rec ->
            val label = featureNames.getOrElse(idx) { "" }
            when (rec.shapeType) {
                3, 13, 23 -> { // PolyLine
                    val valid = rec.parts.filter { it.size >= 2 }
                    if (valid.isNotEmpty()) polylines.add(NamedFeature(label, valid))
                }
                5, 15, 25 -> { // Polygon
                    val valid = rec.parts.filter { it.size >= 3 }
                    if (valid.isNotEmpty()) polygons.add(NamedFeature(label, valid))
                }
            }
        }
        return ParsedBoundary(layerName, polygons, polylines)
    }

    /**
     * Reads DBF attribute table and returns the best "name-like" character field values,
     * one entry per record (same order as the SHP file).
     */
    private fun readDbfNames(bytes: ByteArray): List<String> {
        if (bytes.size < 32) return emptyList()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numRecords = bb.getInt(4)
        val headerSize = bb.getShort(8).toInt() and 0xFFFF
        val recordSize = bb.getShort(10).toInt() and 0xFFFF
        if (headerSize < 32 || recordSize < 1 || numRecords <= 0) return emptyList()

        data class FieldDesc(val name: String, val type: Char, val offset: Int, val length: Int)
        val fields = mutableListOf<FieldDesc>()
        var pos = 32
        var fieldOffset = 1 // byte 0 of each record is deletion flag
        while (pos + 32 <= minOf(headerSize, bytes.size) && bytes[pos] != 0x0D.toByte()) {
            val nameEnd = (0 until 11).firstOrNull { bytes[pos + it] == 0x00.toByte() } ?: 11
            val fieldName = String(bytes, pos, nameEnd).trim()
            val fieldType = bytes[pos + 11].toInt().toChar()
            val fieldLen  = bytes[pos + 16].toInt() and 0xFF
            if (fieldName.isNotEmpty() && fieldLen > 0)
                fields.add(FieldDesc(fieldName, fieldType, fieldOffset, fieldLen))
            fieldOffset += fieldLen
            pos += 32
        }

        // Priority order for "name" field — covers PH admin boundaries and common GIS exports
        val preferred = listOf("name", "adm4_en", "adm3_en", "adm2_en", "adm1_en",
                               "label", "nom", "nam", "nombre", "description", "NAME")
        val nameField = fields.firstOrNull { it.name.lowercase() in preferred && it.type == 'C' }
            ?: fields.firstOrNull { it.type == 'C' && it.length >= 3 }
            ?: return emptyList()

        val names = mutableListOf<String>()
        var recordStart = headerSize
        repeat(numRecords) {
            if (recordStart + recordSize > bytes.size) { names.add(""); recordStart += recordSize; return@repeat }
            val deleted = bytes[recordStart] == 0x2A.toByte()
            val value = if (!deleted) {
                val start = recordStart + nameField.offset
                val end   = minOf(start + nameField.length, bytes.size)
                if (start < bytes.size) String(bytes, start, end - start, Charsets.UTF_8).trim()
                else ""
            } else ""
            names.add(value)
            recordStart += recordSize
        }
        return names
    }

    private fun parseKmlCoords(text: String): List<GeoPoint> =
        text.trim().split("\\s+".toRegex()).mapNotNull { coord ->
            val parts = coord.split(',')
            val lon = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return@mapNotNull null
            GeoPoint(lat, lon)
        }
}
