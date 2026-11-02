package com.taptrack.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.PathParser

/**
 * Status pins — same shapes, colours and glyphs as the web app (web/src/lib/tsIcons.tsx).
 * Glyphs are drawn on a 24×24 grid; circles are written as two arcs so PathParser can read them.
 */
private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private val GLYPH: Map<String, String> = mapOf(
    "proposed" to "M6 21V4h10l-2 4 2 4H6",                                            // flag
    "surveyed" to circle(11f, 11f, 5.5f) + "M15.5 15.5 20 20",                         // magnifier
    "approved" to "M5.5 12.5l4 4 9-9",                                                 // check
    "under_construction" to "M4 20l8-8M13 4l7 7-3 3-7-7z",                             // hammer
    "installed" to "M4 9h9a3 3 0 0 1 3 3v3M8 9V5.5M5 5.5h6M16 18.5v1",                 // faucet
    "operational" to "M12 3.5c3.2 4.2 5.5 7 5.5 9.8a5.5 5.5 0 0 1-11 0c0-2.8 2.3-5.6 5.5-9.8z", // drop (filled)
    "under_repair" to "M12 4.5L20.5 19h-17zM12 10v4M12 16.6v.4",                       // warning
    "inactive" to "M9 7v10M15 7v10",                                                   // pause
    "decommissioned" to "M7 7l10 10M17 7L7 17",                                        // ✕
    "cancelled" to circle(12f, 12f, 6.5f) + "M7.5 16.5l9-9",                           // ⊘
)
private const val PIN_PATH =
    "M15 1.5C7.6 1.5 1.5 7.4 1.5 14.7 1.5 24.6 15 38.5 15 38.5s13.5-13.9 13.5-23.8C28.5 7.4 22.4 1.5 15 1.5z"

private val glyphCache = mutableMapOf<String, Path>()
private fun glyphPath(key: String): Path? = GLYPH[key]?.let { d ->
    glyphCache.getOrPut(key) { PathParser.createPathFromPathData(d) }
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val SLATE = 0xFF475569.toInt()
private const val DARK = 0xFF0F172A.toInt()

/** Map pin (28×37 dp like the web; 36×48 dp when selected). Cancelled = white dashed pin. */
fun statusPinDrawable(context: Context, status: String, color: Int, selected: Boolean = false): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val wDp = if (selected) 36f else 28f
    val hDp = if (selected) 48f else 37f
    val pad = 2f * dp                                   // room for the shadow
    val w = (wDp * dp + pad * 2).toInt(); val h = (hDp * dp + pad * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cancelled = status == "cancelled"

    // viewBox 30×40 → pixels
    val sx = wDp * dp / 30f; val sy = hDp * dp / 40f
    c.save(); c.translate(pad, pad); c.scale(sx, sy)

    val pin = PathParser.createPathFromPathData(PIN_PATH)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; this.color = if (cancelled) WHITE else color
        setShadowLayer(1.5f, 0f, 1f, 0x73000000)
    }
    c.drawPath(pin, fill)
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 3f else 2f
        this.color = when { selected -> DARK; cancelled -> SLATE; else -> WHITE }
        if (cancelled) pathEffect = DashPathEffect(floatArrayOf(3f, 2.5f), 0f)
    }
    c.drawPath(pin, border)

    // glyph: translate(6 5.5) scale(.75), stroke 2.7 on the 24-grid
    glyphPath(status)?.let { g ->
        c.translate(6f, 5.5f); c.scale(0.75f, 0.75f)
        drawGlyph(c, status, g, if (cancelled) SLATE else WHITE)
    }
    c.restore()
    return BitmapDrawable(context.resources, bmp)
}

/** Small round badge (coloured circle + white glyph) for the legend and lists. */
fun statusBadgeBitmap(context: Context, status: String, color: Int, sizeDp: Float = 18f): Bitmap {
    val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cancelled = status == "cancelled"
    val r = px / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (cancelled) WHITE else color })
    if (cancelled) c.drawCircle(r, r, r - 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; this.color = SLATE })
    glyphPath(status)?.let { g ->
        val s = px * 0.62f / 24f
        c.save(); c.translate((px - 24f * s) / 2f, (px - 24f * s) / 2f); c.scale(s, s)
        drawGlyph(c, status, g, if (cancelled) SLATE else WHITE)
        c.restore()
    }
    return bmp
}

private fun drawGlyph(c: Canvas, status: String, g: Path, glyphColor: Int) {
    if (status == "operational") {
        c.drawPath(g, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = glyphColor })
    }
    c.drawPath(g, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.7f; color = glyphColor
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    })
}
