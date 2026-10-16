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

/** Drží MapView a vrstvu s časy pohromadě (jeden FrameLayout, vrstva propouští dotyky na mapu). */
class MapHolder(private val ctx: Context, private val vm: AppViewModel) {
    val mapView: MapView
    val overlay: OverlayView
    val root: FrameLayout
    var map: MapLibreMap? = null
        private set
    private var wantLocation = false

    init {
        MapLibre.getInstance(ctx)
        mapView = MapView(ctx)
        mapView.onCreate(null)
        overlay = OverlayView(ctx, vm)
        root = FrameLayout(ctx)
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(mapView, FrameLayout.LayoutParams(full, full))
        root.addView(overlay, FrameLayout.LayoutParams(full, full))
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> map?.let { overlay.updateTransform(it) } }
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
            m.setStyle(Style.Builder().fromJson(mapStyle())) { if (wantLocation) enableLocation() }
            m.addOnCameraMoveListener { overlay.updateTransform(m) }
            m.addOnCameraIdleListener { overlay.updateTransform(m) }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                if (!overlay.tap(pt.x, pt.y) && !vm.sheetMini) vm.closeSheet()
                true
            }
            overlay.updateTransform(m)
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
        snapshotFlow { vm.renderTick to vm.expanded.size }.collect { holder.overlay.invalidate() }
    }
    return holder
}

@Composable
fun MapHost(holder: MapHolder, modifier: Modifier = Modifier) {
    AndroidView(factory = { holder.root }, modifier = modifier)
}
