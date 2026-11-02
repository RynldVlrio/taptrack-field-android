package com.taptrack.app.utils

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun createLocationDotBitmap(context: Context): Bitmap {
    val dp = context.resources.displayMetrics.density
    val size = (22 * dp).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    canvas.drawCircle(cx + dp, cy + dp, cx - dp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0); style = Paint.Style.FILL
    })
    canvas.drawCircle(cx, cy, cx - dp * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    })
    canvas.drawCircle(cx, cy, cx - dp * 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8"); style = Paint.Style.FILL
    })
    return bitmap
}

/**
 * Colored teardrop pin for user-defined landmarks.
 * [iconType] controls the white symbol drawn inside the badge.
 */
fun createLandmarkMarkerBitmap(
    context: Context,
    color: Int = Color.parseColor("#FF9800"),
    iconType: String = "landmark"
): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val size = (48 * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val cx = size / 2f
    val badgeR = size * 0.30f
    val badgeCy = badgeR + size * 0.04f
    val neckY = badgeCy + badgeR * 0.60f
    val tipY = size * 0.97f

    val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(55, 0, 0, 0); style = Paint.Style.FILL }
    val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.FILL }
    val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = dp * 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // shadow
    canvas.drawCircle(cx + dp, badgeCy + dp, badgeR, shadowPaint)
    canvas.drawPath(Path().apply {
        moveTo(cx - badgeR * 0.42f + dp, neckY + dp)
        lineTo(cx + badgeR * 0.42f + dp, neckY + dp)
        lineTo(cx + dp, tipY + dp); close()
    }, shadowPaint)

    // badge + pointer
    canvas.drawCircle(cx, badgeCy, badgeR, colorPaint)
    canvas.drawPath(Path().apply {
        moveTo(cx - badgeR * 0.42f, neckY)
        lineTo(cx + badgeR * 0.42f, neckY)
        lineTo(cx, tipY); close()
    }, colorPaint)

    // white border ring
    canvas.drawCircle(cx, badgeCy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp * 1.6f
    })

    // draw icon symbol
    val r = badgeR * 0.50f
    when (iconType) {
        "office"                            -> drawIconBuilding(canvas, cx, badgeCy, r, whiteFill)
        "filtration", "treatment_plant"     -> drawIconWTP(canvas, cx, badgeCy, r, whiteFill)
        "pump", "pump_station"              -> drawIconPump(canvas, cx, badgeCy, r, whiteFill, whiteStroke)
        "dosing_station"                    -> drawIconFlask(canvas, cx, badgeCy, r, whiteFill)
        "valve", "gate_valve"               -> drawIconGateValve(canvas, cx, badgeCy, r, whiteFill, whiteStroke)
        "gauge", "pressure_valve"           -> drawIconGauge(canvas, cx, badgeCy, r, whiteFill, whiteStroke)
        "reservoir"                         -> drawIconReservoir(canvas, cx, badgeCy, r, whiteFill, whiteStroke)
        else                                -> drawIconStar(canvas, cx, badgeCy, badgeR, whiteFill)
    }

    return BitmapDrawable(context.resources, bmp)
}

// ── Icon drawing helpers ─────────────────────────────────────────────────────

private fun drawIconStar(canvas: Canvas, cx: Float, cy: Float, badgeR: Float, paint: Paint) {
    val outer = badgeR * 0.52f
    val inner = outer * 0.42f
    val path = Path()
    for (i in 0 until 5) {
        val outerAngle = (-PI / 2 + 2 * PI * i / 5).toFloat()
        val innerAngle = (outerAngle + PI / 5).toFloat()
        val ox = cx + outer * cos(outerAngle)
        val oy = cy + outer * sin(outerAngle)
        val ix = cx + inner * cos(innerAngle)
        val iy = cy + inner * sin(innerAngle)
        if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
        path.lineTo(ix, iy)
    }
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawIconWaterDrop(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
    val circY = cy + r * 0.14f
    val circR = r * 0.60f
    canvas.drawCircle(cx, circY, circR, paint)
    canvas.drawPath(Path().apply {
        moveTo(cx, cy - r * 0.82f)
        lineTo(cx - circR * 0.92f, circY)
        lineTo(cx + circR * 0.92f, circY)
        close()
    }, paint)
}

private fun drawIconBuilding(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
    val top = cy - r * 0.90f
    val roofBot = cy - r * 0.32f
    val bot = cy + r * 0.72f
    val left = cx - r * 0.62f
    val right = cx + r * 0.62f
    // Roof triangle
    canvas.drawPath(Path().apply {
        moveTo(cx, top)
        lineTo(left - r * 0.08f, roofBot)
        lineTo(right + r * 0.08f, roofBot)
        close()
    }, paint)
    // Body
    canvas.drawRect(left, roofBot, right, bot, paint)
}

private fun drawIconWTP(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint) {
    // Water Treatment Plant: cylindrical tank (left) + building with peaked roof (right)
    val tankCx = cx - r * 0.42f
    val tankR = r * 0.34f
    val tankTop = cy - r * 0.60f
    val tankBot = cy + r * 0.56f
    canvas.drawRect(tankCx - tankR, tankTop, tankCx + tankR, tankBot, fill)
    canvas.drawOval(RectF(tankCx - tankR, tankTop - tankR * 0.45f, tankCx + tankR, tankTop + tankR * 0.45f), fill)
    canvas.drawOval(RectF(tankCx - tankR, tankBot - tankR * 0.45f, tankCx + tankR, tankBot + tankR * 0.45f), fill)
    val bL = cx + r * 0.06f; val bR = cx + r * 0.88f
    val bT = cy - r * 0.50f; val bB = cy + r * 0.56f
    canvas.drawRect(bL, bT, bR, bB, fill)
    canvas.drawPath(Path().apply {
        moveTo((bL + bR) / 2f, cy - r * 0.84f); lineTo(bL, bT); lineTo(bR, bT); close()
    }, fill)
}

private fun drawIconPump(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint, stroke: Paint) {
    // Circle casing + right-pointing triangle (play-button / pump symbol)
    canvas.drawCircle(cx, cy, r * 0.86f, stroke)
    val triR = r * 0.52f
    canvas.drawPath(Path().apply {
        moveTo(cx - triR * 0.50f, cy - triR * 0.87f)
        lineTo(cx + triR, cy)
        lineTo(cx - triR * 0.50f, cy + triR * 0.87f)
        close()
    }, fill)
}

private fun drawIconFlask(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
    val neckHalf = r * 0.26f
    val neckTop = cy - r * 0.72f
    val neckBot = cy - r * 0.08f
    val bulbHalf = r * 0.68f
    val bulbBot = cy + r * 0.72f
    // Neck
    canvas.drawRect(cx - neckHalf, neckTop, cx + neckHalf, neckBot + r * 0.12f, paint)
    // Mouth/lip
    canvas.drawRect(cx - neckHalf * 1.6f, neckTop, cx + neckHalf * 1.6f, neckTop + r * 0.20f, paint)
    // Flask body (tapering from neck to wide base)
    canvas.drawPath(Path().apply {
        moveTo(cx - neckHalf, neckBot)
        lineTo(cx - bulbHalf, cy + r * 0.08f)
        lineTo(cx - bulbHalf, bulbBot)
        lineTo(cx + bulbHalf, bulbBot)
        lineTo(cx + bulbHalf, cy + r * 0.08f)
        lineTo(cx + neckHalf, neckBot)
        close()
    }, paint)
}

private fun drawIconGateValve(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint, stroke: Paint) {
    // Gate valve: bowtie body + stem + handwheel circle at top
    val vCy = cy + r * 0.16f
    canvas.drawPath(Path().apply {
        moveTo(cx, vCy); lineTo(cx - r, vCy - r * 0.65f); lineTo(cx - r, vCy + r * 0.65f); close()
    }, fill)
    canvas.drawPath(Path().apply {
        moveTo(cx, vCy); lineTo(cx + r, vCy - r * 0.65f); lineTo(cx + r, vCy + r * 0.65f); close()
    }, fill)
    val stemW = r * 0.12f
    canvas.drawRect(cx - stemW, vCy - r * 0.65f, cx + stemW, cy - r * 0.40f, fill)
    canvas.drawCircle(cx, cy - r * 0.70f, r * 0.26f, stroke)
}

private fun drawIconGauge(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint, stroke: Paint) {
    canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 145f, 250f, false, stroke)
    canvas.drawCircle(cx, cy, r * 0.20f, fill)
    // Needle pointing upper-right (~-50° from vertical = 40°)
    val needleAngle = -1.92  // radians ≈ -110°
    canvas.drawLine(
        cx, cy,
        cx + r * 0.72f * cos(needleAngle).toFloat(),
        cy + r * 0.72f * sin(needleAngle).toFloat(),
        stroke
    )
}

private fun drawIconArrowUp(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
    val arrowTop = cy - r * 0.88f
    val headBot = cy - r * 0.08f
    val headW = r * 0.66f
    val shaftW = r * 0.30f
    val shaftBot = cy + r * 0.72f
    // Arrow head
    canvas.drawPath(Path().apply {
        moveTo(cx, arrowTop)
        lineTo(cx - headW, headBot)
        lineTo(cx + headW, headBot)
        close()
    }, paint)
    // Shaft
    canvas.drawRect(cx - shaftW, headBot, cx + shaftW, shaftBot, paint)
}

private fun drawIconReservoir(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint, stroke: Paint) {
    val left = cx - r * 0.86f
    val right = cx + r * 0.86f
    val top = cy - r * 0.66f
    val bot = cy + r * 0.66f
    val corner = r * 0.14f
    // Rectangle outline
    canvas.drawRoundRect(RectF(left, top, right, bot), corner, corner, stroke)
    // Two horizontal "water level" lines inside
    val insetX = r * 0.24f
    canvas.drawLine(left + insetX, cy - r * 0.22f, right - insetX, cy - r * 0.22f, stroke)
    canvas.drawLine(left + insetX, cy + r * 0.24f, right - insetX, cy + r * 0.24f, stroke)
}

/** Small text-label bitmap for boundary layer names. */
fun createBoundaryLabelBitmap(context: Context, text: String, borderColor: Int): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * dp
        color = borderColor or 0xFF000000.toInt()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(2f * dp, 0f, 1f * dp, Color.argb(100, 0, 0, 0))
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.FILL
    }
    val bounds = android.graphics.Rect()
    textPaint.getTextBounds(text, 0, text.length, bounds)
    val pad = 4f * dp
    val w = bounds.width() + pad * 2
    val h = bounds.height() + pad * 2
    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawRoundRect(android.graphics.RectF(0f, 0f, w, h), pad, pad, bgPaint)
    canvas.drawText(text, pad, h - pad, textPaint)
    return BitmapDrawable(context.resources, bmp)
}

/** Red crosshair pin dropped on map when a search result is selected. */
fun createSearchPinBitmap(context: Context): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val size = (48 * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val cx = size / 2f
    val badgeR = size * 0.30f
    val badgeCy = badgeR + size * 0.04f
    val neckY = badgeCy + badgeR * 0.60f
    val tipY = size * 0.97f
    val pinColor = Color.parseColor("#E53935")

    val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinColor; style = Paint.Style.FILL }
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 0, 0, 0); style = Paint.Style.FILL }

    canvas.drawCircle(cx + dp, badgeCy + dp, badgeR, shadowPaint)
    canvas.drawPath(Path().apply {
        moveTo(cx - badgeR * 0.42f + dp, neckY + dp)
        lineTo(cx + badgeR * 0.42f + dp, neckY + dp)
        lineTo(cx + dp, tipY + dp); close()
    }, shadowPaint)

    canvas.drawCircle(cx, badgeCy, badgeR, colorPaint)
    canvas.drawPath(Path().apply {
        moveTo(cx - badgeR * 0.42f, neckY)
        lineTo(cx + badgeR * 0.42f, neckY)
        lineTo(cx, tipY); close()
    }, colorPaint)

    canvas.drawCircle(cx, badgeCy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp * 1.6f
    })

    val arm = badgeR * 0.44f
    val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp * 1.6f; strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(cx - arm, badgeCy, cx + arm, badgeCy, crossPaint)
    canvas.drawLine(cx, badgeCy - arm, cx, badgeCy + arm, crossPaint)
    canvas.drawCircle(cx, badgeCy, arm * 0.38f, crossPaint)

    return BitmapDrawable(context.resources, bmp)
}

fun createTapMarkerBitmap(context: Context, color: Int = Color.parseColor("#0277BD")): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val size = (56 * dp).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val cx = size / 2f
    val badgeR = size * 0.295f
    val badgeCy = badgeR + size * 0.035f
    val neckY = badgeCy + badgeR * 0.58f
    val tipY = size * 0.97f

    val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(55, 0, 0, 0); style = Paint.Style.FILL }
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.FILL }

    val sdx = dp * 1.5f; val sdy = dp * 1.5f
    canvas.drawCircle(cx + sdx, badgeCy + sdy, badgeR, shadowPaint)
    canvas.drawPath(Path().apply {
        moveTo(cx - badgeR * 0.42f + sdx, neckY + sdy)
        lineTo(cx + badgeR * 0.42f + sdx, neckY + sdy)
        lineTo(cx + sdx, tipY + sdy); close()
    }, shadowPaint)

    canvas.drawCircle(cx, badgeCy, badgeR, colorPaint)
    canvas.drawPath(Path().apply {
        moveTo(cx - badgeR * 0.42f, neckY)
        lineTo(cx + badgeR * 0.42f, neckY)
        lineTo(cx, tipY); close()
    }, colorPaint)

    canvas.drawCircle(cx, badgeCy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp * 1.8f
    })

    val lw = badgeR * 0.195f; val rr = lw / 2f
    val iTop = badgeCy - badgeR * 0.55f; val iBot = badgeCy + badgeR * 0.35f
    val iLeft = cx - badgeR * 0.55f; val iRight = cx + badgeR * 0.55f
    val iW = iRight - iLeft

    canvas.drawRoundRect(RectF(iLeft, iTop, iLeft + iW * 0.70f, iTop + lw), rr, rr, whitePaint)
    canvas.drawRoundRect(RectF(iLeft, iTop, iLeft + lw * 1.2f, iTop + lw * 2.2f), rr, rr, whitePaint)
    val bodyX = iLeft + iW * 0.30f
    canvas.drawRoundRect(RectF(bodyX - lw / 2f, iTop + lw * 0.5f, bodyX + lw / 2f, iBot - lw), rr, rr, whitePaint)
    val elbowY = iBot - lw * 1.3f
    canvas.drawRoundRect(RectF(bodyX - lw / 2f, elbowY - lw / 2f, iLeft + iW * 0.85f, elbowY + lw / 2f), rr, rr, whitePaint)
    val nozzleL = iLeft + iW * 0.85f - lw
    canvas.drawRoundRect(RectF(nozzleL, elbowY + lw / 2f, nozzleL + lw, iBot + lw * 0.6f), rr, rr, whitePaint)
    val drCx = nozzleL + lw / 2f; val drTopY = iBot + lw * 0.6f + dp * 0.5f; val drR = lw * 0.80f
    canvas.drawCircle(drCx, drTopY + drR, drR, whitePaint)
    canvas.drawPath(Path().apply {
        moveTo(drCx, drTopY)
        lineTo(drCx - drR * 0.65f, drTopY + drR)
        lineTo(drCx + drR * 0.65f, drTopY + drR); close()
    }, whitePaint)

    return BitmapDrawable(context.resources, bmp)
}
