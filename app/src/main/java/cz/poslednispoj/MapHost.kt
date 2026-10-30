package cz.poslednispoj

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Mapový podklad: turistická mapa Mapy.com (je-li při sestavení k dispozici API klíč),
 * jinak OpenStreetMap + turistické trasy z Waymarked Trails.
 */
private fun mapStyle(): String {
    val key = BuildConfig.MAPY_API_KEY
    if (key.isNotBlank()) return """
{
  "version": 8,
  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "sources": {
    "mapy": {
      "type": "raster",
      "tiles": ["https://api.mapy.com/v1/maptiles/outdoor/256@2x/{z}/{x}/{y}?apikey=$key"],
      "tileSize": 256,
      "minzoom": 0,
      "maxzoom": 18,
      "attribution": "© Seznam.cz a.s. a další"
    }
  },
  "layers": [ { "id": "mapy", "type": "raster", "source": "mapy" } ]
}
"""
    return """
{
  "version": 8,
  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© přispěvatelé OpenStreetMap"
    },
    "hiking": {
      "type": "raster",
      "tiles": ["https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 18,
      "attribution": "Turistické trasy © waymarkedtrails.org"
    }
  },
  "layers": [
    { "id": "osm", "type": "raster", "source": "osm" },
    { "id": "hiking", "type": "raster", "source": "hiking", "paint": { "raster-opacity": 0.85 } }
  ]
}
"""
}

/** Drží MapView a vrstvy s časy (kreslí je přímo mapa, takže drží na místě). */
class MapHolder(private val ctx: Context, private val vm: AppViewModel) {
    val mapView: MapView
    val layers = MapLayers(vm)
    val root: FrameLayout
    var map: MapLibreMap? = null
        private set
    private var wantLocation = false

    init {
        MapLibre.getInstance(ctx)
        mapView = MapView(ctx)
        mapView.onCreate(null)
        root = FrameLayout(ctx)
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(mapView, FrameLayout.LayoutParams(full, full))
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            if (BuildConfig.MAPY_API_KEY.isNotBlank()) {
                // logo a copyright Mapy.com zobrazujeme sami vlevo dole
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = false
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(50.0, 14.45)).zoom(8.6).build()
            m.setStyle(Style.Builder().fromJson(mapStyle())) { style ->
                layers.install(style, ctx)
                if (wantLocation) enableLocation()
            }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val dens = ctx.resources.displayMetrics.density
                val tick = { root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) }
                val leg = if (vm.selected >= 0) layers.hitLeg(m, pt.x, pt.y, dens) else -1
                when {
                    // klepnutí na cestu = jako klepnutí na spoj v detailu (rozbalí / sbalí jeho trasu)
                    leg >= 0 -> { tick(); vm.toggleRoute(leg) }
                    // při rozbaleném detailu klepnutí do mapy jen sbalí detail, zastávka zůstává
                    vm.selected >= 0 && !vm.sheetMini -> vm.toggleMini()
                    else -> {
                        val id = layers.hitTest(m, pt.x, pt.y, dens)
                        if (id >= 0) { tick(); vm.select(id) }
                    }
                }
                true
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun enableLocation() {
        wantLocation = true
        val m = map ?: return
        val style = m.style ?: return
        val lc = m.locationComponent
        try {
            if (!lc.isLocationComponentActivated) {
                lc.activateLocationComponent(
                    LocationComponentActivationOptions.builder(ctx, style).useDefaultLocationEngine(true).build()
                )
            }
            lc.isLocationComponentEnabled = true
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.NORMAL
        } catch (e: Exception) {
            // bez oprávnění nebo bez polohy – mapa funguje i tak
        }
    }

    /** Posune mapu na moji polohu. Vrací false, když poloha zatím není známá. */
    fun zoomToMe(): Boolean {
        val m = map ?: return false
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) return false
        val loc = lc.lastKnownLocation ?: return false
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 13.0))
        return true
    }

    /** Plynulý přelet na oblast; dole se nechá místo pro detail. */
    fun flyTo(b: DoubleArray, bottomDp: Int) {
        val m = map ?: return
        val d = ctx.resources.displayMetrics.density
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(b[0], b[1])).include(LatLng(b[2], b[3])).build()
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * d).toInt(), (110 * d).toInt(), (40 * d).toInt(), (bottomDp * d).toInt()), 700)
        } catch (_: Exception) {}
    }

    fun moveTo(lat: Double, lon: Double, zoom: Double) {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
    }

    fun zoomBy(delta: Double) { map?.animateCamera(CameraUpdateFactory.zoomBy(delta)) }
}

@Composable
fun rememberMapHolder(vm: AppViewModel): MapHolder {
    val ctx = LocalContext.current
    val holder = remember { MapHolder(ctx, vm) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, holder) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> holder.mapView.onStart()
                Lifecycle.Event.ON_RESUME -> holder.mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.mapView.onPause()
                Lifecycle.Event.ON_STOP -> holder.mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> holder.mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // každá změna výsledku nebo výběru překreslí vrstvu
    LaunchedEffect(holder) {
        snapshotFlow { vm.renderTick to vm.expanded.size }.collect { holder.layers.update() }
    }
    LaunchedEffect(holder) {
        snapshotFlow { vm.cameraFit }.collect { b ->
            if (b != null) {
                val hDp = (holder.root.height / holder.root.resources.displayMetrics.density).toInt()
                holder.flyTo(b, if (vm.sheetMini) 110 else (hDp * 0.5).toInt() + 30)
                vm.cameraFit = null
            }
        }
    }
    LaunchedEffect(holder) {
        snapshotFlow { vm.cameraRequest }.collect { r ->
            if (r != null) { holder.moveTo(r[0], r[1], r[2]); vm.cameraRequest = null }
        }
    }
    return holder
}

@Composable
fun MapHost(holder: MapHolder, modifier: Modifier = Modifier) {
    AndroidView(factory = { holder.root }, modifier = modifier)
}
