package com.taptrack.app.utils

import org.osmdroid.util.GeoPoint
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One SHP record = one feature (may have multiple rings/parts). */
data class ShapeRecord(
    val shapeType: Int,
    val parts: List<List<GeoPoint>>
)

object ShapefileReader {

    /**
     * Parses an ESRI shapefile (.shp) stream and returns one [ShapeRecord] per feature,
     * preserving the same record order as the companion .dbf attribute table.
     */
    fun readShp(inputStream: InputStream): List<ShapeRecord> {
        val bytes = inputStream.readBytes()
        if (bytes.size < 100) return emptyList()

        val buf = ByteBuffer.wrap(bytes)
        val records = mutableListOf<ShapeRecord>()

        buf.position(100) // skip file header

        while (buf.remaining() >= 8) {
            buf.order(ByteOrder.BIG_ENDIAN)
            @Suppress("UNUSED_VARIABLE") val recNum = buf.int
            val contentLengthBytes = buf.int * 2

            if (contentLengthBytes < 4 || buf.remaining() < contentLengthBytes) break

            val contentStart = buf.position()
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val shapeType = buf.int

            try {
                val parts: List<List<GeoPoint>> = when (shapeType) {
                    3, 13, 23 -> readPartsAndPoints(buf) // PolyLine + Z/M variants
                    5, 15, 25 -> readPartsAndPoints(buf) // Polygon + Z/M variants
                    else       -> emptyList()
                }
                records.add(ShapeRecord(shapeType, parts))
            } catch (_: Exception) {
                records.add(ShapeRecord(shapeType, emptyList()))
            }

            buf.position(contentStart + contentLengthBytes)
        }

        return records
    }

    private fun readPartsAndPoints(buf: ByteBuffer): List<List<GeoPoint>> {
        buf.position(buf.position() + 32) // skip bounding box
        val numParts = buf.int
        val numPoints = buf.int
        if (numParts <= 0 || numPoints <= 0 || numParts > numPoints) return emptyList()

        val partStarts = IntArray(numParts) { buf.int }
        val allPoints = Array(numPoints) {
            val x = buf.double // longitude
            val y = buf.double // latitude
            GeoPoint(y, x)
        }

        return (0 until numParts).map { i ->
            val start = partStarts[i]
            val end = if (i < numParts - 1) partStarts[i + 1] else numPoints
            allPoints.slice(start until end)
        }
    }
}
