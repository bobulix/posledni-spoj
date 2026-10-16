package cz.poslednispoj

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * Průhledná vrstva nad mapou: tečky zastávek, časy posledních odjezdů, tečky dřívějších spojů
 * a zvýrazněná cesta. Kreslí se sama, mapa pod ní dál reaguje na posun a přiblížení.
 */
class OverlayView(ctx: Context, private val vm: AppViewModel) : View(ctx) {
    companion object {
        /** Velikost čtverce pro shlukování časů (dp). Větší = méně časů najednou. */
        const val CLUSTER_DP = 64f
    }

    private val dp = resources.displayMetrics.density

    // převod Web Mercator -> obrazovka: x = ax * mx + bx, y = ay * my + by
    private var ax = 0.0; private var bx = 0.0; private var ay = 0.0; private var by = 0.0
    private var ready = false
    private var mx = DoubleArray(0); private var my = DoubleArray(0)
    private var metaRef: Meta? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensed }
    private val nameText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); color = 0xFF1C2620.toInt()
    }
    private val rect = RectF()
    private val path = Path()

    private val boxes = ArrayList<FloatArray>()
    private val labelHits = ArrayList<FloatArray>() // x0,y0,x1,y1,uzel

    init {
        isClickable = false
        isFocusable = false
    }

    private fun mercX(lon: Double) = (lon + 180.0) / 360.0
    private fun mercY(lat: Double): Double {
        val r = lat * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    private fun ensureNodes() {
        val m = vm.meta ?: return
        if (m === metaRef) return
        metaRef = m
        mx = DoubleArray(m.size) { mercX(m.lon[it]) }
        my = DoubleArray(m.size) { mercY(m.lat[it]) }
    }

    /** Zjistí aktuální převod souřadnic z mapy (dva referenční body; mapa se neotáčí ani nenaklání). */
    fun updateTransform(map: MapLibreMap) {
        val p1 = map.projection.toScreenLocation(LatLng(49.5, 13.5))
        val p2 = map.projection.toScreenLocation(LatLng(50.5, 15.5))
        val m1x = mercX(13.5); val m2x = mercX(15.5); val m1y = mercY(49.5); val m2y = mercY(50.5)
        ax = (p2.x - p1.x) / (m2x - m1x); bx = p1.x - ax * m1x
        ay = (p2.y - p1.y) / (m2y - m1y); by = p1.y - ay * m1y
        ready = true
        invalidate()
    }

    private fun sx(i: Int) = (ax * mx[i] + bx).toFloat()
    private fun sy(i: Int) = (ay * my[i] + by).toFloat()

    /** Pixelů (v dp) na metr – stejné měřítko jako ve webovém prototypu. */
    private fun scale(): Double = ax / (40075016.7 * cos(50.0 * PI / 180.0)) / dp

    private fun hit(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        for (b in boxes) if (x0 < b[2] && x1 > b[0] && y0 < b[3] && y1 > b[1]) return true
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureNodes()
        val m = vm.meta ?: return
        val p = vm.profile ?: return
        if (!ready) return
        val tgt = vm.target
        if (tgt.size != m.size) return
        val sc = scale()
        val w = width.toFloat(); val h = height.toFloat()
        val alpha = (vm.opacity * 255).toInt().coerceIn(0, 255)
        val now = vm.nowMin
        val grey = 0xFFA2AC9F.toInt()

        // rozbalené trasy spojů (čárkovaně) a zvýrazněná cesta
        stroke.pathEffect = DashPathEffect(floatArrayOf(7 * dp, 5 * dp), 0f)
        stroke.color = 0xFF1C2620.toInt(); stroke.strokeWidth = 3 * dp
        for (nodes in vm.expanded.values) {
            path.reset()
            nodes.forEachIndexed { k, n -> if (k == 0) path.moveTo(sx(n), sy(n)) else path.lineTo(sx(n), sy(n)) }
            canvas.drawPath(path, stroke)
        }
        stroke.pathEffect = null
        for (nodes in vm.expanded.values) for (n in nodes) {
            fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(sx(n), sy(n), 3 * dp, fill)
            stroke.strokeWidth = 1.5f * dp; canvas.drawCircle(sx(n), sy(n), 3 * dp, stroke)
        }
        stroke.color = 0xFFC8321F.toInt(); stroke.strokeWidth = 5 * dp
        for ((isWalk, nodes) in vm.journeyPaths) {
            stroke.pathEffect = if (isWalk) DashPathEffect(floatArrayOf(2 * dp, 7 * dp), 0f) else null
            path.reset()
            nodes.forEachIndexed { k, n -> if (k == 0) path.moveTo(sx(n), sy(n)) else path.lineTo(sx(n), sy(n)) }
            canvas.drawPath(path, stroke)
        }
        stroke.pathEffect = null

        // viditelné zastávky
        val margin = 30 * dp
        val vis = ArrayList<Int>()
        for (i in 0 until m.size) {
            val x = sx(i); val y = sy(i)
            if (x < -margin || y < -margin || x > w + margin || y > h + margin) continue
            vis.add(i)
        }
        val r = (if (sc > 0.03) 4.5f else if (sc > 0.012) 3.5f else 2.4f) * dp
        for (i in vis) {
            if (tgt[i]) continue
            val l = p.latest[i]
            if (l < 0) {
                if (sc > 0.015) { fill.color = grey; fill.alpha = alpha; canvas.drawCircle(sx(i), sy(i), 1.6f * dp, fill) }
                continue
            }
            fill.color = if (now >= 0 && l < now) grey else vm.binOf(l).bg
            fill.alpha = alpha
            canvas.drawCircle(sx(i), sy(i), r, fill)
        }

        // štítky s časy: mapu dělíme na čtverce (připevněné k mapě, ne k obrazovce)
        // a v každém ukážeme jen zastávku s nejpozdějším odjezdem; ostatní zůstanou jako tečky
        boxes.clear(); labelHits.clear()
        val imp = vm.importance
        val cell = CLUSTER_DP * dp
        val best = HashMap<Long, Int>()
        for (i in vis) {
            if (p.latest[i] < 0 || tgt[i]) continue
            val key = (Math.floor(ax * mx[i] / cell).toLong() shl 32) xor (Math.floor(ay * my[i] / cell).toLong() and 0xFFFFFFFFL)
            val cur = best[key]
            if (cur == null || p.latest[i] > p.latest[cur] ||
                (p.latest[i] == p.latest[cur] && (if (i < imp.size) imp[i] else 0) > (if (cur < imp.size) imp[cur] else 0))
            ) best[key] = i
        }
        val lab = best.values.sortedWith(
            compareByDescending<Int> { p.latest[it] }.thenByDescending { if (it < imp.size) imp[it] else 0 }
        )
        val maxLabels = if (sc > 0.004) 900 else 200
        val bigLabels = sc > 0.03
        text.textSize = (if (bigLabels) 15f else 13f) * dp
        nameText.textSize = 12f * dp
        val showNames = sc > 0.045
        val showDots = sc > 0.02
        val bh = (if (bigLabels) 19f else 17f) * dp
        var count = 0
        for (i in lab) {
            if (count > maxLabels) break
            val x = sx(i); val y = sy(i)
            val txt = hm(p.latest[i])
            val bw = text.measureText(txt) + 8 * dp
            val nPrev = if (showDots) min(3, p.prof[i].size - 1) else 0
            val dr = (bh - 2 * dp) / 6f
            val dw = if (nPrev > 0) 2 * dp + dr * 2 else 0f
            val x0 = x + 5 * dp; val y0 = y - bh - 3 * dp
            if (hit(x0, y0, x0 + bw + dw, y0 + bh)) continue
            boxes.add(floatArrayOf(x0 - dp, y0 - dp, x0 + bw + dw + dp, y0 + bh + dp)); count++
            labelHits.add(floatArrayOf(x0 - 3 * dp, y0 - 3 * dp, x0 + bw + dw + 3 * dp, y0 + bh + 3 * dp, i.toFloat()))
            val missed = now >= 0 && p.latest[i] < now
            val b = vm.binOf(p.latest[i])
            fill.color = if (missed) grey else b.bg; fill.alpha = alpha
            rect.set(x0, y0, x0 + bw, y0 + bh)
            canvas.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
            text.color = if (missed) 0xFFFFFFFF.toInt() else b.fg; text.alpha = alpha
            val fm = text.fontMetrics
            canvas.drawText(txt, x0 + 4 * dp, y0 + bh / 2 - (fm.ascent + fm.descent) / 2, text)
            for (k in 0 until nPrev) {
                val pd = p.prof[i][k + 1].dep
                fill.color = if (now >= 0 && pd < now) grey else vm.binOf(pd).bg; fill.alpha = alpha
                canvas.drawCircle(x0 + bw + 2 * dp + dr, y0 + dr + k * (2 * dr + dp), dr, fill)
            }
            if (showNames) {
                val nm = m.names[i]
                val nw = nameText.measureText(nm)
                val ny0 = y0 + bh + dp
                if (!hit(x0, ny0, x0 + nw, ny0 + 14 * dp)) {
                    boxes.add(floatArrayOf(x0, ny0, x0 + nw, ny0 + 14 * dp))
                    nameText.alpha = alpha
                    canvas.drawText(nm, x0, ny0 + 11 * dp, nameText)
                }
            }
        }
        fill.alpha = 255

        // vybraná zastávka
        val sel = vm.selected
        if (sel in 0 until m.size) {
            fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(sx(sel), sy(sel), 8 * dp, fill)
            stroke.color = 0xFF1C2620.toInt(); stroke.strokeWidth = 3 * dp
            canvas.drawCircle(sx(sel), sy(sel), 8 * dp, stroke)
        }
    }

    /** Klepnutí na mapu: nejdřív štítek s časem, pak nejbližší tečka. Vrací true, pokud něco trefilo. */
    fun tap(x: Float, y: Float): Boolean {
        val p = vm.profile ?: return false
        val m = vm.meta ?: return false
        for (k in labelHits.indices.reversed()) {
            val b = labelHits[k]
            if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) { vm.select(b[4].toInt()); return true }
        }
        var best = -1
        var bd = (22 * dp) * (22 * dp)
        for (i in 0 until m.size) {
            if (p.latest[i] < 0 || vm.target.getOrElse(i) { false }) continue
            val dx = sx(i) - x; val dy = sy(i) - y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        if (best >= 0) { vm.select(best); return true }
        return false
    }
}
