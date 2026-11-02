package com.taptrack.app.ui.components

import android.view.MotionEvent
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Google-Maps-like two-finger rotation for osmdroid.
 *
 *  - Pinch (fingers closing / opening) only zooms — small accidental twists are ignored.
 *  - The map starts rotating only after the fingers have turned past a threshold
 *    (15°, or 30° if the gesture is already a clear pinch), then follows the fingers smoothly
 *    with no jump.
 *  - Zoom keeps working while rotating (events are never consumed here).
 */
class SmoothRotationOverlay(private val map: MapView) : Overlay() {

    private var tracking = false
    private var rotating = false
    private var startAngle = 0f        // finger angle when the 2-finger gesture began
    private var startSpan = 0f         // finger distance when the gesture began
    private var baseAngle = 0f         // finger angle when rotation kicked in
    private var baseOrientation = 0f   // map orientation when rotation kicked in

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                tracking = true; rotating = false
                startAngle = angle(event); startSpan = span(event)
            }
            MotionEvent.ACTION_MOVE -> if (tracking && event.pointerCount >= 2) {
                val a = angle(event)
                if (!rotating) {
                    val turned = abs(norm(a - startAngle))
                    val pinched = if (startSpan > 0f) abs(span(event) / startSpan - 1f) else 0f
                    val threshold = if (pinched > 0.10f) 30f else 15f
                    if (turned > threshold) {
                        rotating = true
                        baseAngle = a                        // start from here → no sudden jump
                        baseOrientation = map.mapOrientation
                    }
                } else {
                    map.mapOrientation = (baseOrientation + norm(a - baseAngle) + 360f) % 360f
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) { tracking = false; rotating = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; rotating = false }
        }
        return false   // let osmdroid handle pan / pinch-zoom as usual
    }

    /** Same convention as osmdroid's own rotation detector (degrees). */
    private fun angle(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(0) - e.getY(1)).toDouble(), (e.getX(0) - e.getX(1)).toDouble())).toFloat()

    private fun span(e: MotionEvent): Float = hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()

    /** Wrap to −180..180. */
    private fun norm(d: Float): Float { var x = d % 360f; if (x > 180f) x -= 360f; if (x < -180f) x += 360f; return x }
}
