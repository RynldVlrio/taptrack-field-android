package com.taptrack.app.utils

import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.*

fun writeGpsExif(filePath: String, lat: Double, lon: Double) {
    try {
        val exif = ExifInterface(filePath)

        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, lat.toExifDms())
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, lon.toExifDms())
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")

        val now = Date()
        exif.setAttribute(
            ExifInterface.TAG_GPS_DATESTAMP,
            SimpleDateFormat("yyyy:MM:dd", Locale.US).format(now)
        )
        exif.setAttribute(
            ExifInterface.TAG_GPS_TIMESTAMP,
            SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(now)
        )

        exif.saveAttributes()
    } catch (_: Exception) {
        // Non-fatal: photo is still usable without EXIF
    }
}

// Converts a decimal degree value to the EXIF DMS rational string format:
// "degrees/1,minutes/1,seconds_numerator/10000"
private fun Double.toExifDms(): String {
    val abs = kotlin.math.abs(this)
    val deg = abs.toInt()
    val minFloat = (abs - deg) * 60.0
    val min = minFloat.toInt()
    val secNum = ((minFloat - min) * 60.0 * 10_000.0).toLong()
    return "$deg/1,$min/1,$secNum/10000"
}
