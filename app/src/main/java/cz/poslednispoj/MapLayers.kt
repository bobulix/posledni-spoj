package cz.poslednispoj

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.google.gson.JsonArray
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Tečky zastávek, štítky s časy, tečky dřívějších spojů a zvýrazněná cesta
 * jako vrstvy přímo v mapě – drží na místě při posunu i přiblížení.
 *
 * Shlukování: pro každou celočíselnou úroveň přiblížení se mapa rozdělí na čtverce
 * CLUSTER_DP × CLUSTER_DP; v každém se ukáže štítek zastávky s nejpozdějším odjezdem.
 * Výsledek je u každé zastávky uložen jako „od jaké úrovně se ukazuje“ (z0), takže se
 * štítky mění jen při přechodu mezi úrovněmi, ne při každém pohybu prstem.
 */
class MapLayers(private val vm: AppViewModel) {
    companion object {
        const val CLUSTER_DP = 64.0
        const val MIN_Z = 6
        const val MAX_Z = 17
        const val DOTS_MIN_ZOOM = 10f     // tečky dřívějších spojů až při bližším pohledu
        const val TEXT_SIZE = 13f
        const val FONT = "Noto Sans Bold"
        private const val DOT_IMG = 12f   // velikost obrázku tečky v px
        private const val GREY = "#A2AC9F"

        val labelLayerIds = (MIN_Z..MAX_Z).map { "lab-$it" }
        val dotLayerIds = (MIN_Z..MAX_Z).flatMap { z -> (0..2).map { "pdot-$z-$it" } }
        const val MISSED = 99 // pásmo „dnes už ujel“
    }

    private var style: Style? = null
    private var builtFor: Profile? = null
    private var builtNow = -2
    private var builtBy = -1

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun install(style: Style, ctx: android.content.Context) {
        this.style = style
        val d = ctx.resources.displayMetrics.density
        // obdélník pod časem (jednobarevný, barví se podle pásma)
        val box = Bitmap.createBitmap((24 * d).toInt(), (24 * d).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(box).drawRoundRect(RectF(0f, 0f, box.width.toFloat(), box.height.toFloat()), 4 * d, 4 * d,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        style.addImage("box", box, true)
        val dot = Bitmap.createBitmap((DOT_IMG * d).toInt(), (DOT_IMG * d).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(dot).drawCircle(dot.width / 2f, dot.height / 2f, dot.width / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        style.addImage("dot", dot, true)

        // velké zdroje (zastávky, štítky) jen do úrovně 12 – výš se jen zvětšují; šetří paměť
        // Každá úroveň štítků má vlastní zdroj, který se dlaždicuje jen od své úrovně (minZoom),
        // takže se tisíce štítků nekopírují do dlaždic všech úrovní (jinak dojde paměť).
        fun opts(minZ: Int, maxZ: Int) = org.maplibre.android.style.sources.GeoJsonOptions().withMinZoom(minZ).withMaxZoom(maxZ).withBuffer(64)
        style.addSource(GeoJsonSource("stops-on", FeatureCollection.fromFeatures(emptyList()), opts(0, 10)))
        style.addSource(GeoJsonSource("stops-off", FeatureCollection.fromFeatures(emptyList()), opts(9, 10)))
        for (z in MIN_Z..MAX_Z) style.addSource(GeoJsonSource("labels-$z", FeatureCollection.fromFeatures(emptyList()),
            opts(if (z == MIN_Z) 0 else z, maxOf(z, 10))))
        for (id in listOf("routes", "routepts", "journey", "sel")) {
            style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())))
        }
        // nedosažitelné zastávky šedě, až při přiblížení
        style.addLayer(CircleLayer("stops-off", "stops-off").withProperties(
            PropertyFactory.circleColor(GREY),
            PropertyFactory.circleRadius(1.6f),
        ).apply { minZoom = 9.6f })
        style.addLayer(CircleLayer("stops-on", "stops-on").withProperties(
            PropertyFactory.circleColor(bgOf("b")),
            PropertyFactory.circleRadius(
                Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(8, 2.4f), Expression.stop(10.5, 3.5f), Expression.stop(12, 4.5f))
            ),
        ))
        // rozbalené trasy spojů (čárkovaně) a jejich zastávky
        style.addLayer(LineLayer("routes", "routes").withProperties(
            PropertyFactory.lineColor("#1C2620"), PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2.3f, 1.7f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        style.addLayer(CircleLayer("routepts", "routepts").withProperties(
            PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleRadius(3f),
            PropertyFactory.circleStrokeColor("#1C2620"), PropertyFactory.circleStrokeWidth(1.5f),
        ))
        // zvýrazněná cesta
        style.addLayer(LineLayer("journey", "journey").withProperties(
            PropertyFactory.lineColor(toColor(get("col"))), PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(Expression.eq(get("walk"), literal(0))))
        style.addLayer(LineLayer("journey-walk", "journey").withProperties(
            PropertyFactory.lineColor(toColor(get("col"))), PropertyFactory.lineWidth(5f),
            PropertyFactory.lineDasharray(arrayOf(0.4f, 1.4f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ).withFilter(Expression.eq(get("walk"), literal(1))))
        // štítky a tečky dřívějších spojů: jedna vrstva na každou úroveň, od které se zastávka ukazuje
        for (z in MIN_Z..MAX_Z) {
            style.addLayer(SymbolLayer("lab-$z", "labels-$z").withProperties(
                PropertyFactory.textField(get("t")),
                PropertyFactory.textFont(arrayOf(FONT)),
                PropertyFactory.textSize(TEXT_SIZE),
                PropertyFactory.textColor(fgOf("b")),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM_LEFT),
                PropertyFactory.textOffset(arrayOf(0.4f, -0.35f)),
                PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.iconImage("box"),
                PropertyFactory.iconColor(bgOf("b")),
                PropertyFactory.iconTextFit(Property.ICON_TEXT_FIT_BOTH),
                PropertyFactory.iconTextFitPadding(arrayOf(2f, 4f, 2f, 4f)),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ).apply { minZoom = if (z == MIN_Z) 0f else z.toFloat() })
            // tečky dřívějších spojů: až tři ve sloupci vpravo od štítku (pásma v d0, d1, d2)
            val r = 17.0 / 6.0 // jako výška obdélníku pod časem
            val size = (2 * r / DOT_IMG)
            for (k in 0..2) {
                fun off(w: Int) = arrayOf(((1.0 + w * 0.572 * TEXT_SIZE + 0.29 * TEXT_SIZE + 8 + 2 + r) / size).toFloat(),
                    ((-21.0 + r + k * (2 * r + 1)) / size).toFloat())
                style.addLayer(SymbolLayer("pdot-$z-$k", "labels-$z").withProperties(
                    PropertyFactory.iconImage("dot"),
                    PropertyFactory.iconColor(bgOf("d$k")),
                    PropertyFactory.iconSize(size.toFloat()),
                    PropertyFactory.iconOffset(Expression.switchCase(
                        Expression.eq(get("w"), literal(4)), literal(off(4)), literal(off(3)))),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                    PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                ).withFilter(Expression.has("d$k"))
                    .apply { minZoom = maxOf(z.toFloat(), DOTS_MIN_ZOOM) })
            }
        }
        style.addLayer(CircleLayer("sel", "sel").withProperties(
            PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#1C2620"), PropertyFactory.circleStrokeWidth(3f),
        ))
        builtFor = null
        update()
    }

    /** Barva pozadí / textu podle čísla pásma ve vlastnosti [prop] (jiné číslo = šedá). */
    private fun bgOf(prop: String): Expression = Expression.match(
        *(listOf<Expression>(get(prop)) + BINS.indices.flatMap { listOf(literal(it), literal(hex(BINS[it].bg))) } + literal(GREY)).toTypedArray())
    private fun fgOf(prop: String): Expression = Expression.match(
        *(listOf<Expression>(get(prop)) + BINS.indices.flatMap { listOf(literal(it), literal(hex(BINS[it].fg))) } + literal("#FFFFFF")).toTypedArray())

    private fun mercX(lon: Double) = (lon + 180.0) / 360.0
    private fun mercY(lat: Double): Double {
        val r = lat * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    /** Šířka štítku v dp (odhad podle počtu číslic písma Noto Sans Bold). */
    private fun labelWidth(t: String): Double {
        var w = 0.0
        for (ch in t) w += if (ch == ':') 0.29 else 0.572
        return w * TEXT_SIZE + 8
    }

    /** Přepočítá data vrstev; drahé části jen při změně výsledku výpočtu. */
    fun update() {
        val st = style ?: return
        if (!st.isFullyLoaded) return
        val m = vm.meta ?: return
        val p = vm.profile
        if (p !== builtFor || vm.nowMin != builtNow || vm.arriveBy != builtBy) {
            buildStopsAndLabels(st, m, p)
            builtFor = p; builtNow = vm.nowMin; builtBy = vm.arriveBy
        }
        buildSelection(st, m)
        applyOpacity(st)
    }

    private fun binIdx(dep: Int): Int =
        if (vm.nowMin >= 0 && dep < vm.nowMin) MISSED else BINS.indexOf(vm.binOf(dep))

    private fun colorOf(dep: Int): Pair<String, String> {
        if (vm.nowMin >= 0 && dep < vm.nowMin) return GREY to "#FFFFFF"
        val b = vm.binOf(dep)
        return hex(b.bg) to hex(b.fg)
    }

    private fun buildStopsAndLabels(st: Style, m: Meta, p: Profile?) {
        val tgt = vm.target
        val stops = ArrayList<Feature>(m.size)
        val stopsOff = ArrayList<Feature>()
        val labels = (MIN_Z..MAX_Z).map { ArrayList<Feature>() }
        fun publish() {
            st.getSourceAs<GeoJsonSource>("stops-on")?.setGeoJson(FeatureCollection.fromFeatures(stops))
            st.getSourceAs<GeoJsonSource>("stops-off")?.setGeoJson(FeatureCollection.fromFeatures(stopsOff))
            for (z in MIN_Z..MAX_Z) st.getSourceAs<GeoJsonSource>("labels-$z")?.setGeoJson(FeatureCollection.fromFeatures(labels[z - MIN_Z]))
        }
        if (p == null || tgt.size != m.size) { publish(); return }
        val imp = vm.importance
        val mx = DoubleArray(m.size) { mercX(m.lon[it]) }
        val my = DoubleArray(m.size) { mercY(m.lat[it]) }
        val reach = (0 until m.size).filter { p.latest[it] >= 0 && !tgt[it] }
        val texts = HashMap<Int, String>()
        val widths = HashMap<Int, Double>()
        for (i in reach) {
            val t = hm(p.latest[i]); texts[i] = t
            val nPrev = min(3, p.prof[i].size - 1)
            widths[i] = labelWidth(t) + (if (nPrev > 0) 9.0 else 0.0)
        }
        val prio = compareByDescending<Int> { p.latest[it] }.thenByDescending { if (it < imp.size) imp[it] else 0 }

        // z0 = nejnižší úroveň, od které se štítek ukazuje (a ukazuje se i na všech vyšších)
        val z0 = IntArray(m.size) { MAX_Z + 1 }
        for (z in MAX_Z downTo MIN_Z) {
            val world = 512.0 * 2.0.pow(z) // dp na celou šířku světa
            val best = HashMap<Long, Int>()
            for (i in reach) {
                val key = (floor(mx[i] * world / CLUSTER_DP).toLong() shl 32) xor (floor(my[i] * world / CLUSTER_DP).toLong() and 0xFFFFFFFFL)
                val cur = best[key]
                if (cur == null || prio.compare(i, cur) < 0) best[key] = i
            }
            // vyloučení překryvů na této úrovni (deterministicky, podle priority)
            val grid = HashMap<Long, MutableList<DoubleArray>>()
            val shown = HashSet<Int>()
            for (i in best.values.sortedWith(prio)) {
                val x = mx[i] * world; val y = my[i] * world
                val b = doubleArrayOf(x + 1, y - 23, x + 1 + widths[i]!!, y - 2)
                val gx0 = floor(b[0] / 100).toLong(); val gx1 = floor(b[2] / 100).toLong()
                val gy0 = floor(b[1] / 100).toLong(); val gy1 = floor(b[3] / 100).toLong()
                var hit = false
                loop@ for (gx in gx0 - 1..gx1 + 1) for (gy in gy0 - 1..gy1 + 1) {
                    val l = grid[(gx shl 32) xor (gy and 0xFFFFFFFFL)] ?: continue
                    for (o in l) if (b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1]) { hit = true; break@loop }
                }
                if (hit) continue
                shown.add(i)
                grid.getOrPut((gx0 shl 32) xor (gy0 and 0xFFFFFFFFL)) { ArrayList() }.add(b)
            }
            for (i in reach) if (i in shown && z0[i] == z + 1) z0[i] = z
        }

        for (i in 0 until m.size) {
            if (tgt[i]) continue
            val pt = Point.fromLngLat(m.lon[i], m.lat[i])
            val f = Feature.fromGeometry(pt)
            f.addNumberProperty("id", i)
            val l = p.latest[i]
            if (l < 0) { stopsOff.add(f); continue }
            val b = binIdx(l)
            f.addNumberProperty("b", b)
            stops.add(f)
            val lf = Feature.fromGeometry(pt)
            lf.addNumberProperty("id", i)
            lf.addStringProperty("t", texts[i]); lf.addNumberProperty("b", b)
            lf.addNumberProperty("w", texts[i]!!.length - 1) // počet číslic: 3 nebo 4
            // tečky dřívějších spojů (pásma)
            val nPrev = min(3, p.prof[i].size - 1)
            for (k in 0 until nPrev) lf.addNumberProperty("d$k", binIdx(p.prof[i][k + 1].dep))
            labels[z0[i].coerceAtMost(MAX_Z) - MIN_Z].add(lf)
        }
        publish()
    }

    private fun buildSelection(st: Style, m: Meta) {
        val sel = vm.selected
        val selF = if (sel in 0 until m.size) listOf(Feature.fromGeometry(Point.fromLngLat(m.lon[sel], m.lat[sel]))) else emptyList()
        st.getSourceAs<GeoJsonSource>("sel")?.setGeoJson(FeatureCollection.fromFeatures(selF))
        // když je rozbalený konkrétní spoj, je výrazný jen on; ostatní úseky cesty jsou světlejší
        val focus = vm.expanded.keys
        val jf = vm.journeyPaths.mapIndexed { li, g ->
            val f = Feature.fromGeometry(LineString.fromLngLats(g.lat.indices.map { Point.fromLngLat(g.lon[it], g.lat[it]) }))
            f.addNumberProperty("walk", if (g.walk) 1 else 0)
            f.addStringProperty("col", if (focus.isEmpty() || li in focus) "#C8321F" else "#EBA79C")
            f
        }
        st.getSourceAs<GeoJsonSource>("journey")?.setGeoJson(FeatureCollection.fromFeatures(jf))
        val rf = vm.expandedLines.values.filter { it.lat.size >= 2 }.map { g ->
            Feature.fromGeometry(LineString.fromLngLats(g.lat.indices.map { Point.fromLngLat(g.lon[it], g.lat[it]) }))
        }
        st.getSourceAs<GeoJsonSource>("routes")?.setGeoJson(FeatureCollection.fromFeatures(rf))
        val rp = vm.expanded.values.flatMap { it.asIterable() }.map { Feature.fromGeometry(Point.fromLngLat(m.lon[it], m.lat[it])) }
        st.getSourceAs<GeoJsonSource>("routepts")?.setGeoJson(FeatureCollection.fromFeatures(rp))
    }

    private fun applyOpacity(st: Style) {
        val a = vm.opacity
        for (id in labelLayerIds) st.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.textOpacity(a), PropertyFactory.iconOpacity(a))
        for (id in dotLayerIds) st.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.iconOpacity(a))
        st.getLayerAs<CircleLayer>("stops-on")?.setProperties(PropertyFactory.circleOpacity(a))
        st.getLayerAs<CircleLayer>("stops-off")?.setProperties(PropertyFactory.circleOpacity(a))
        // zvýrazněná cesta a rozbalené trasy se zprůhledňují spolu s časy
        for (id in listOf("journey", "journey-walk", "routes")) st.getLayerAs<LineLayer>(id)?.setProperties(PropertyFactory.lineOpacity(a))
        for (id in listOf("routepts", "sel")) st.getLayerAs<CircleLayer>(id)?.setProperties(
            PropertyFactory.circleOpacity(a), PropertyFactory.circleStrokeOpacity(a))
    }

    /** Klepnutí: nejdřív štítek s časem (nebo jeho tečky), pak nejbližší tečka zastávky. Vrací id zastávky nebo -1. */
    fun hitTest(map: MapLibreMap, x: Float, y: Float, density: Float): Int {
        val pad = 6 * density
        val onLabel = map.queryRenderedFeatures(RectF(x - pad, y - pad, x + pad, y + pad), *(labelLayerIds + dotLayerIds).toTypedArray())
        onLabel.firstOrNull()?.getNumberProperty("id")?.let { return it.toInt() }
        val rad = 22 * density
        val near = map.queryRenderedFeatures(RectF(x - rad, y - rad, x + rad, y + rad), "stops-on")
        var best = -1; var bd = Float.MAX_VALUE
        for (f in near) {
            val g = f.geometry() as? Point ?: continue
            val s: PointF = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(g.latitude(), g.longitude()))
            val d = (s.x - x) * (s.x - x) + (s.y - y) * (s.y - y)
            if (d < bd) { bd = d; best = f.getNumberProperty("id")?.toInt() ?: -1 }
        }
        return best
    }
}
