package de.skygazer.myvrnlite.ui

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import de.skygazer.myvrnlite.BuildConfig
import de.skygazer.myvrnlite.R
import de.skygazer.myvrnlite.net.Journey
import de.skygazer.myvrnlite.net.Mode
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * OpenStreetMap (osmdroid) for the Fahrplan: origin/destination pins, a pressed point, and a
 * journey drawn leg by leg in its mode's colour (footpaths dotted). [fit] places things in the
 * part of the map the bottom sheet / side panel leaves free.
 */
class RouteMap(private val context: Context, private val persistView: Boolean = true) {
    val view: MapView

    var onLongPress: ((GeoPoint) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val pins = HashMap<String, Marker>()
    private val route = ArrayList<Overlay>()
    private val prefs = context.getSharedPreferences("fahrplan", Context.MODE_PRIVATE)

    init {
        setup(context)
        view = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
            setMinZoomLevel(4.0)
            setMaxZoomLevel(19.0)
            overlays += MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap?.invoke(); return false }
                override fun longPressHelper(p: GeoPoint): Boolean { onLongPress?.invoke(p); return onLongPress != null }
            })
            // OSM's licence asks for the credit; top left stays clear of the sheet.
            overlays += CopyrightOverlay(context).apply { setAlignBottom(false); setTextSize(10) }
        }
    }

    /** Where the map was last (the VRN area the first time, or if the saved place makes no sense). */
    fun restoreView() {
        var lat = prefs.getFloat("map_lat", HOME_LAT).toDouble()
        var lon = prefs.getFloat("map_lon", HOME_LON).toDouble()
        var zoom = prefs.getFloat("map_zoom", HOME_ZOOM).toDouble()
        // Older versions could save 0,0 (the sea off Africa: a light blue map), see saveView.
        if (lat !in 34.0..72.0 || lon !in -25.0..45.0 || zoom !in 4.0..19.0) {
            lat = HOME_LAT.toDouble()
            lon = HOME_LON.toDouble()
            zoom = HOME_ZOOM.toDouble()
        }
        view.controller.setZoom(zoom)
        view.controller.setCenter(GeoPoint(lat, lon))
    }

    fun saveView() {
        if (!persistView) return  // a connection's route isn't where the search map should reopen
        // Before its first layout (the Fahrplan tab never shown) the map reports 0,0 as its centre.
        if (view.width == 0 || view.height == 0) return
        val c = view.mapCenter
        prefs.edit().putFloat("map_lat", c.latitude.toFloat()).putFloat("map_lon", c.longitude.toFloat())
            .putFloat("map_zoom", view.zoomLevelDouble.toFloat()).apply()
    }

    fun onResume() = view.onResume()

    fun onPause() {
        saveView()
        view.onPause()
    }

    // ---- Pins ------------------------------------------------------------------------

    /** kind: "from", "to" or "pick" (the pressed point). */
    fun setPin(kind: String, lat: Double?, lon: Double?) {
        pins.remove(kind)?.let { view.overlays.remove(it) }
        if (lat != null && lon != null) {
            pins[kind] = marker(GeoPoint(lat, lon), pinIcon(kind)).also { view.overlays += it }
        }
        view.invalidate()
    }

    fun pinPoints(): List<GeoPoint> = listOf("from", "to").mapNotNull { pins[it]?.position }

    private fun pinIcon(kind: String): GradientDrawable = GradientDrawable().apply {
        val size = context.dp(if (kind == "pick") 22 else 20)
        setSize(size, size)
        when (kind) {
            "pick" -> { shape = GradientDrawable.OVAL; setColor(0xffffffff.toInt()); setStroke(context.dp(5), 0xff222222.toInt()) }
            "to" -> { shape = GradientDrawable.RECTANGLE; cornerRadius = context.dp(4).toFloat()
                setColor(context.attrColor(R.attr.brandDark)); setStroke(context.dp(3), 0xffffffff.toInt()) }
            else -> { shape = GradientDrawable.OVAL; setColor(context.attrColor(R.attr.brand)); setStroke(context.dp(3), 0xffffffff.toInt()) }
        }
    }

    private fun marker(p: GeoPoint, icon: GradientDrawable): Marker = Marker(view).apply {
        position = p
        setIcon(icon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
    }

    // ---- Journey ---------------------------------------------------------------------

    /** Draws [j]'s legs and returns all its points (for [fit]). */
    fun drawJourney(j: Journey): List<GeoPoint> {
        route.forEach { view.overlays.remove(it) }
        route.clear()
        val all = ArrayList<GeoPoint>()
        for (leg in j.legs) {
            val pts = leg.coords.map { GeoPoint(it[0], it[1]) }.ifEmpty {
                (listOf(leg.origin) + leg.stops + leg.destination).mapNotNull { s -> s.lat?.let { GeoPoint(it, s.lon!!) } }
            }
            if (pts.size < 2) continue
            all += pts
            val color = context.color(modeColor(leg.mode, onMap = true))
            val walk = leg.mode == Mode.WALK
            if (!walk) route += line(pts, 0xffffffff.toInt(), 9f, dotted = false)  // white casing
            route += line(pts, color, if (walk) 4f else 5f, dotted = walk)
            if (walk) continue
            for (s in leg.stops) {
                if (s.lat != null && s.lon != null) route += marker(GeoPoint(s.lat, s.lon), dot(color, 3, 2))
            }
            for (s in listOf(leg.origin, leg.destination)) {
                if (s.lat != null && s.lon != null) route += marker(GeoPoint(s.lat, s.lon), dot(color, 6, 3))
            }
        }
        view.overlays.addAll(route)
        all.firstOrNull()?.let { setPin("from", it.latitude, it.longitude) }
        all.lastOrNull()?.let { setPin("to", it.latitude, it.longitude) }
        view.invalidate()
        return all
    }

    private fun line(pts: List<GeoPoint>, color: Int, widthDp: Float, dotted: Boolean) = Polyline(view).apply {
        setPoints(pts)
        outlinePaint.apply {
            this.color = color
            strokeWidth = widthDp * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // Round caps on near-zero dashes: a row of dots, like the web map's footpaths.
            if (dotted) pathEffect = DashPathEffect(floatArrayOf(1f, strokeWidth * 2.2f), 0f)
        }
        setInfoWindow(null)
        setOnClickListener { _, _, _ -> false }
    }

    private fun dot(color: Int, radiusDp: Int, strokeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val size = context.dp(radiusDp * 2)
        setSize(size, size)
        setColor(0xffffffff.toInt())
        setStroke(context.dp(strokeDp), color)
    }

    // ---- Fitting -------------------------------------------------------------------------

    /**
     * Shows [points] in the free part of the map: the sheet covers [coveredBottom] px from the
     * bottom, a side panel [coveredLeft] px from the left.
     */
    fun fit(points: List<GeoPoint>, coveredBottom: Int, coveredLeft: Int, maxZoom: Double = 16.0) {
        if (points.isEmpty()) return
        if (view.width == 0 || view.height == 0) {
            view.addOnFirstLayoutListener { _, _, _, _, _ -> fit(points, coveredBottom, coveredLeft, maxZoom) }
            return
        }
        val bottom = minOf(coveredBottom, (view.height * 0.7).toInt())
        val left = minOf(coveredLeft, (view.width * 0.6).toInt())
        val pad = context.dp(36)
        val w = (view.width - left - 2 * pad).coerceAtLeast(context.dp(80))
        val h = (view.height - bottom - 2 * pad - context.dp(16)).coerceAtLeast(context.dp(80))
        // The map's centre is the middle of the free area from now on (also for zoom buttons).
        view.setMapCenterOffset(left / 2, -bottom / 2)
        if (points.size == 1 || points.distinct().size == 1) {
            view.controller.setZoom(15.0)
            view.controller.setCenter(points[0])
        } else {
            val box = BoundingBox.fromGeoPointsSafe(points)
            view.controller.setZoom(MapView.getTileSystem().getBoundingBoxZoom(box, w, h).coerceAtMost(maxZoom))
            view.controller.setCenter(GeoPoint(box.centerLatitude, box.centerLongitude))
        }
        view.invalidate()
    }

    /** Pans so [p] isn't hidden under the bottom [coveredBottom] px. */
    fun reveal(p: GeoPoint, coveredBottom: Int) {
        val pt = view.projection.toPixels(p, null)
        val free = view.height - coveredBottom - context.dp(40)
        if (pt.y > free) view.controller.animateTo(view.projection.fromPixels(view.width / 2, view.height / 2 + pt.y - free / 2))
    }

    companion object {
        // Where the map opens the first time: the VRN area (Mannheim / Ludwigshafen / Heidelberg and around).
        private const val HOME_LAT = 49.44f
        private const val HOME_LON = 8.2f
        private const val HOME_ZOOM = 10f

        private var configured = false

        /** osmdroid: our user agent (OSM tile policy), tiles cached in the app's cache dir. */
        fun setup(context: Context) {
            if (configured) return
            configured = true
            Configuration.getInstance().apply {
                userAgentValue = "myVRNLite/${BuildConfig.VERSION_NAME} (${context.packageName})"
                osmdroidBasePath = File(context.cacheDir, "osmdroid")
                osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
            }
        }

        fun modeColor(mode: Mode, onMap: Boolean = false): Int = when (mode) {
            Mode.TRAIN -> if (onMap) R.color.mode_train_map else R.color.mode_train
            Mode.SBAHN -> R.color.mode_sbahn
            Mode.UBAHN -> R.color.mode_ubahn
            Mode.TRAM -> R.color.mode_tram
            Mode.BUS -> R.color.mode_bus
            Mode.SHIP -> R.color.mode_ship
            Mode.TAXI -> R.color.mode_taxi
            Mode.BIKE -> R.color.mode_bike
            Mode.WALK -> R.color.mode_walk
            Mode.OTHER -> R.color.mode_other
        }

        fun modeIcon(mode: Mode): Int = when (mode) {
            Mode.BUS, Mode.TAXI -> R.drawable.ic_mode_bus
            Mode.TRAM -> R.drawable.ic_mode_tram
            Mode.WALK -> R.drawable.ic_mode_walk
            else -> R.drawable.ic_mode_train
        }
    }
}
