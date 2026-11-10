package com.taptrack.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Makes a camera photo small enough to store and view cheaply:
 *   full  = longest side 1600 px, JPEG 80  (~0.2–0.4 MB instead of 3–5 MB) — GPS and date kept
 *   thumb = longest side 320 px,  JPEG 70  (~20–30 KB) for map popups and lists
 * The picture is turned upright (camera rotation applied), so viewers don't depend on EXIF orientation.
 */
object PhotoCompress {
    class Result(val full: ByteArray, val thumb: ByteArray)

    private const val FULL_PX = 1600
    private const val THUMB_PX = 320
    private val KEEP_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
    )

    fun compress(src: File, tmpDir: File): Result {
        val exif = runCatching { ExifInterface(src.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL

        // decode at a reduced size first (keeps memory low on older phones)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Photo file is not a readable image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FULL_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Photo file is not a readable image")

        val upright = rotate(scaleTo(decoded, FULL_PX), orientation)
        val fullBytes = jpeg(upright, 80)
        val thumbBytes = jpeg(scaleTo(upright, THUMB_PX), 70)

        // put GPS / date back on the small copy
        val withExif = if (exif != null) runCatching {
            val tmp = File(tmpDir, "c_${System.nanoTime()}.jpg")
            tmp.writeBytes(fullBytes)
            val out = ExifInterface(tmp.absolutePath)
            for (t in KEEP_TAGS) exif.getAttribute(t)?.let { out.setAttribute(t, it) }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.saveAttributes()
            tmp.readBytes().also { tmp.delete() }
        }.getOrDefault(fullBytes) else fullBytes

        return Result(withExif, thumbBytes)
    }

    private fun scaleTo(b: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxPx) return b
        val f = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
