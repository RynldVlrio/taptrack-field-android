package com.taptrack.app.ui.components

import android.annotation.SuppressLint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taptrack.app.ui.screens.map.BoundaryOverlay
import com.taptrack.app.utils.createLocationDotBitmap
import com.taptrack.app.utils.createSearchPinBitmap
import com.taptrack.app.utils.createTapMarkerBitmap
import com.taptrack.app.utils.statusPinDrawable
import com.taptrack.app.utils.layerLabelDrawable
import com.taptrack.app.utils.getLastKnownLocation
import com.taptrack.app.utils.tileSourceFor
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon as OsmPolygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** A point drawn on the map (tapstand, applicant house…). */
data class MapPin(
    val id: String,
    val lat: Double,
    val lng: Double,
    val color: Int,          // ARGB
    val title: String,
    val small: Boolean = false, // small dot (e.g. applicant house) instead of a tapstand pin
    val status: String? = null, // tapstand status → web-style pin with status glyph
)

// Pinamalayan, Oriental Mindoro
const val PROJECT_LAT = 13.036
const val PROJECT_LNG = 121.488

@SuppressLint("MissingPermission")
@Composable
fun OsmMapView(
    pins: List<MapPin>,
    modifier: Modifier = Modifier,
    satellite: Boolean = false,
    locateTrigger: Int = 0,
    searchTarget: GeoPoint? = null,
    initialLat: Double = PROJECT_LAT,
    initialLng: Double = PROJECT_LNG,
    zoom: Double = 14.0,
    routePoints: List<GeoPoint>? = null,
    boundaryOverlays: List<BoundaryOverlay> = emptyList(),
    searchPin: GeoPoint? = null,
    onPinClick: (String) -> Unit = {},
    onSearchPinClick: (() -> Unit)? = null,
    onMapViewReady: (MapView) -> Unit = {},
    /** Fires on every long press. nearUser=true when pressing within ~80px of the GPS dot. */
    onLongPress: ((lat: Double, lng: Double, nearUser: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(tileSourceFor(satellite))
            setMultiTouchControls(true)
            setMaxZoomLevel(21.0)
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(initialLat, initialLng))
        }
    }

    LaunchedEffect(mapView) { onMapViewReady(mapView) }

    LaunchedEffect(satellite) {
        mapView.setTileSource(tileSourceFor(satellite))
        mapView.invalidate()
    }

    val myLocationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            enableMyLocation()
            val dot = createLocationDotBitmap(context)
            setPersonIcon(dot)
            setPersonAnchor(.5f, .5f)
            mapView.overlays.add(0, this)
        }
    }

    remember {
        // Google-Maps-like: pinch = zoom only; rotate only after a deliberate twist
        SmoothRotationOverlay(mapView).also { mapView.overlays.add(it) }
    }

    val onLongPressRef = rememberUpdatedState(onLongPress)
    remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val handler = onLongPressRef.value ?: return false
                val userLoc = myLocationOverlay.myLocation
                val nearUser = if (userLoc != null) {
                    val userPx = mapView.projection.toPixels(userLoc, android.graphics.Point())
                    val pressPx = mapView.projection.toPixels(p, android.graphics.Point())
                    val dx = (userPx.x - pressPx.x).toDouble()
                    val dy = (userPx.y - pressPx.y).toDouble()
                    kotlin.math.sqrt(dx * dx + dy * dy) <= 80.0
                } else false
                val finalLat = if (nearUser && userLoc != null) userLoc.latitude else p.latitude
                val finalLng = if (nearUser && userLoc != null) userLoc.longitude else p.longitude
                handler(finalLat, finalLng, nearUser)
                return true
            }
        }).also { mapView.overlays.add(it) }
    }

    // ── Boundary / pipeline layers ───────────────────────────────────────────
    val activeBoundaryOverlays = remember { mutableListOf<org.osmdroid.views.overlay.Overlay>() }
    LaunchedEffect(boundaryOverlays) {
        activeBoundaryOverlays.forEach { mapView.overlays.remove(it) }
        activeBoundaryOverlays.clear()
        val density = context.resources.displayMetrics.density
        for (overlay in boundaryOverlays) {
            for (feature in overlay.polygons) {
                for (ring in feature.rings) {
                    val poly = OsmPolygon().apply {
                        points = ring.toMutableList()
                        fillPaint.color = overlay.fillColor
                        fillPaint.style = android.graphics.Paint.Style.FILL
                        outlinePaint.color = overlay.borderColor
                        outlinePaint.strokeWidth = 2.5f * density
                        outlinePaint.style = android.graphics.Paint.Style.STROKE
                    }
                    mapView.overlays.add(0, poly)
                    activeBoundaryOverlays.add(poly)
                }
            }
            for (feature in overlay.polylines) {
                for (pts in feature.rings) {
                    val polyline = Polyline().apply {
                        setPoints(pts)
                        outlinePaint.color = overlay.borderColor
                        outlinePaint.strokeWidth = 3.5f * density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        outlinePaint.isAntiAlias = true
                    }
                    mapView.overlays.add(0, polyline)
                    activeBoundaryOverlays.add(polyline)
                }
            }
            if (overlay.showLabel) {
                for (feature in overlay.polygons + overlay.polylines) {
                    val label = feature.name.ifBlank { if (feature in overlay.polygons) overlay.name else "" }
                    if (label.isBlank()) continue
                    val centroid = computeFeatureCentroid(feature.rings) ?: continue
                    val marker = Marker(mapView).apply {
                        position = centroid
                        icon = layerLabelDrawable(context, label)
                        title = label
                        setAnchor(0.5f, 0.5f)
                    }
                    mapView.overlays.add(marker)
                    activeBoundaryOverlays.add(marker)
                }
            }
        }
        mapView.invalidate()
    }

    // ── Route line ─────────────────────────────────────────────────────────────
    val routeOverlay = remember { mutableStateOf<Polyline?>(null) }
    LaunchedEffect(routePoints) {
        routeOverlay.value?.let { mapView.overlays.remove(it) }
        if (!routePoints.isNullOrEmpty()) {
            val density = context.resources.displayMetrics.density
            val line = Polyline().apply {
                setPoints(routePoints)
                outlinePaint.color = android.graphics.Color.parseColor("#4A90E2")
                outlinePaint.strokeWidth = 9f * density
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            mapView.overlays.add(0, line)
            routeOverlay.value = line
        } else {
            routeOverlay.value = null
        }
        mapView.invalidate()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); myLocationOverlay.enableMyLocation() }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); myLocationOverlay.disableMyLocation() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            myLocationOverlay.disableMyLocation()
            mapView.onDetach()
        }
    }

    LaunchedEffect(locateTrigger) {
        if (locateTrigger > 0) {
            val location = myLocationOverlay.myLocation
                ?: context.getLastKnownLocation()?.let { GeoPoint(it.latitude, it.longitude) }
            if (location != null) {
                mapView.controller.animateTo(location, 17.0, 800L)
            } else {
                myLocationOverlay.runOnFirstFix {
                    myLocationOverlay.myLocation?.let { point -> mapView.post { mapView.controller.animateTo(point) } }
                }
            }
        }
    }

    LaunchedEffect(searchTarget) {
        searchTarget?.let { mapView.controller.animateTo(it, 18.0, 1000L) }
    }

    // ── Search pin ─────────────────────────────────────────────────────────────
    val searchPinIcon = remember { createSearchPinBitmap(context) }
    val searchPinMarkerRef = remember { mutableStateOf<Marker?>(null) }
    val onSearchPinClickRef = rememberUpdatedState(onSearchPinClick)
    LaunchedEffect(searchPin) {
        searchPinMarkerRef.value?.let { mapView.overlays.remove(it) }
        searchPinMarkerRef.value = null
        if (searchPin != null) {
            val marker = Marker(mapView).apply {
                position = searchPin
                icon = searchPinIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> onSearchPinClickRef.value?.invoke(); true }
            }
            mapView.overlays.add(marker)
            searchPinMarkerRef.value = marker
        }
        mapView.invalidate()
    }

    // ── Pins (tapstands coloured by status; small dots for houses) ───────────────
    val iconCache = remember { mutableMapOf<String, BitmapDrawable>() }
    val pinMarkers = remember { mutableListOf<Marker>() }
    val onPinClickRef = rememberUpdatedState(onPinClick)
    LaunchedEffect(pins) {
        pinMarkers.forEach { mapView.overlays.remove(it) }
        pinMarkers.clear()
        // houses first so tapstand pins draw on top
        pins.sortedBy { when { it.small -> 0; it.status == "proposed" || it.status == "cancelled" -> 1; else -> 2 } }.forEach { pin ->
            val key = "${pin.color}|${pin.small}|${pin.status}"
            val icon = iconCache.getOrPut(key) {
                when {
                    pin.small -> createDotBitmap(context, pin.color)
                    pin.status != null -> statusPinDrawable(context, pin.status, pin.color)
                    else -> createTapMarkerBitmap(context, pin.color)
                }
            }
            val marker = Marker(mapView).apply {
                position = GeoPoint(pin.lat, pin.lng)
                title = pin.title
                this.icon = icon
                if (pin.small) setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                else setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> onPinClickRef.value(pin.id); true }
            }
            mapView.overlays.add(marker)
            pinMarkers.add(marker)
        }
        mapView.invalidate()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun createDotBitmap(context: android.content.Context, color: Int): BitmapDrawable {
    val dp = context.resources.displayMetrics.density
    val size = (14 * dp).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color
    c.drawCircle(size / 2f, size / 2f, size / 2f - 1.5f * dp, p)
    return BitmapDrawable(context.resources, bmp)
}

private fun computeFeatureCentroid(rings: List<List<GeoPoint>>): GeoPoint? {
    val all = rings.flatten().ifEmpty { return null }
    return GeoPoint(all.sumOf { it.latitude } / all.size, all.sumOf { it.longitude } / all.size)
}

/** Map with a fixed centre pin — drag the map to choose a location. */
@Composable
fun CenterPinMapView(
    lat: Double,
    lng: Double,
    modifier: Modifier = Modifier,
    satellite: Boolean = false,
    onLocationPicked: (Double, Double) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLocationPicked by rememberUpdatedState(onLocationPicked)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(tileSourceFor(satellite))
            setMultiTouchControls(true)
            setMaxZoomLevel(21.0)
            controller.setZoom(18.0)
            controller.setCenter(GeoPoint(lat, lng))
        }
    }
    LaunchedEffect(satellite) { mapView.setTileSource(tileSourceFor(satellite)); mapView.invalidate() }

    DisposableEffect(Unit) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                val c = mapView.mapCenter; currentOnLocationPicked(c.latitude, c.longitude); return false
            }
            override fun onZoom(event: ZoomEvent): Boolean {
                val c = mapView.mapCenter; currentOnLocationPicked(c.latitude, c.longitude); return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); mapView.onDetach() }
    }

    val pinColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(width = 18.dp, height = 6.dp).background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(50)))
            Canvas(modifier = Modifier.size(52.dp).offset(y = (-26).dp)) {
                val w = size.width; val h = size.height; val cx = w / 2f
                val radius = w * 0.38f; val cy = radius + 3f
                drawCircle(Color.Black.copy(alpha = 0.18f), radius = radius + 2f, center = Offset(cx + 2f, cy + 4f))
                drawCircle(pinColor, radius = radius, center = Offset(cx, cy))
                drawCircle(Color.White, radius = radius * 0.4f, center = Offset(cx, cy))
                val path = Path().apply {
                    moveTo(cx - radius * 0.5f, cy + radius * 0.65f)
                    lineTo(cx + radius * 0.5f, cy + radius * 0.65f)
                    lineTo(cx, h - 2f)
                    close()
                }
                drawPath(path, pinColor)
            }
        }
    }
}
