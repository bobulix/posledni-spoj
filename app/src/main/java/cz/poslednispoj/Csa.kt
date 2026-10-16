package cz.poslednispoj

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Spoje jednoho dne, seřazené sestupně podle odjezdu (časy v minutách od půlnoci). */
class Day(
    val n: Int,
    val u: IntArray, val v: IntArray,
    val dep: IntArray, val arr: IntArray,
    val trip: IntArray,
    val flag: ByteArray, // 1 = lze nastoupit v u, 2 = lze vystoupit ve v
    val nTrips: Int,
)

/** Pěší přechody mezi blízkými zastávkami (sousednost v CSR podobě). */
class Walk(val start: IntArray, val to: IntArray, val dur: IntArray)

/**
 * Položka profilu zastávky: odjezd [dep] dorazí do cíle v [eta].
 * Jízda: [conn] = spoj, kterým odjíždím, [exit] = spoj, ze kterého vystupuji.
 * Pěšky: [walk] = sousední zastávka, kam dojdu. [next] = pokračování cesty.
 */
class Entry(val dep: Int, val eta: Int, val conn: Int, val exit: Int, val walk: Int, val next: Entry?)

sealed class Leg {
    data class Ride(val trip: Int, val from: Int, val dep: Int, val to: Int, val arr: Int) : Leg()
    data class WalkLeg(val from: Int, val to: Int) : Leg()
}

class RouteStop(val node: Int, val arr: Int, var dep: Int)

class Profile(val latest: IntArray, val prof: Array<ArrayList<Entry>>)

object Csa {
    private const val INF = 32000

    fun parseDay(bytes: ByteArray): Day {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.getInt(0)
        fun col(k: Int) = IntArray(n) { i -> bb.getShort(4 + (k * n + i) * 2).toInt() and 0xFFFF }
        val u = col(0); val v = col(1); val dep = col(2); val arr = col(3); val trip = col(4)
        val flag = ByteArray(n) { i -> bb.get(4 + n * 10 + i) }
        var m = 0
        for (t in trip) if (t > m) m = t
        return Day(n, u, v, dep, arr, trip, flag, m + 1)
    }

    fun buildWalk(nNodes: Int, flat: IntArray): Walk {
        val deg = IntArray(nNodes + 1)
        var i = 0
        while (i < flat.size) { deg[flat[i] + 1]++; deg[flat[i + 1] + 1]++; i += 3 }
        for (k in 0 until nNodes) deg[k + 1] += deg[k]
        val to = IntArray(deg[nNodes]); val dur = IntArray(deg[nNodes])
        val pos = deg.copyOf(nNodes)
        i = 0
        while (i < flat.size) {
            val a = flat[i]; val b = flat[i + 1]; val d = flat[i + 2]
            to[pos[a]] = b; dur[pos[a]] = d; pos[a]++
            to[pos[b]] = a; dur[pos[b]] = d; pos[b]++
            i += 3
        }
        return Walk(deg, to, dur)
    }

    /** Nejlepší položka s odjezdem >= x (seznam je seřazený sestupně podle odjezdu). */
    private fun query(list: ArrayList<Entry>, x: Int): Entry? {
        var lo = 0; var hi = list.size - 1; var r = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (list[m].dep >= x) { r = m; lo = m + 1 } else hi = m - 1
        }
        return if (r < 0) null else list[r]
    }

    private fun insert(list: ArrayList<Entry>, e: Entry): Boolean {
        for (x in list) if (x.dep >= e.dep && x.eta <= e.eta) return false
        list.removeAll { it.dep <= e.dep && it.eta >= e.eta }
        var pos = list.indexOfFirst { it.dep < e.dep }
        if (pos < 0) pos = list.size
        list.add(pos, e)
        return true
    }

    /**
     * Pro každou zastávku spočítá smysluplné odjezdy, se kterými se dostanu do cíle do času [arriveBy].
     * Každý dřívější odjezd v profilu dorazí do cíle opravdu dřív než ten pozdější.
     */
    fun profile(
        day: Day, walk: Walk, nNodes: Int,
        target: BooleanArray, arriveBy: Int, change: IntArray, allowed: BooleanArray?,
    ): Profile {
        val prof = Array(nNodes) { ArrayList<Entry>(2) }
        val tripEta = IntArray(day.nTrips) { INF }
        val tripExit = IntArray(day.nTrips) { -1 }
        val tripNext = arrayOfNulls<Entry>(day.nTrips)
        for (i in 0 until day.n) {
            val d = day.dep[i]
            if (d > arriveBy) continue
            val t = day.trip[i]
            if (allowed != null && !allowed[t]) continue
            val f = day.flag[i].toInt()
            if ((f and 2) != 0) {
                val b = day.v[i]; val a = day.arr[i]
                var eta = INF; var nx: Entry? = null
                if (target[b]) {
                    if (a <= arriveBy) eta = a
                } else {
                    val q = query(prof[b], a + change[b])
                    if (q != null) { eta = q.eta; nx = q }
                }
                if (eta < tripEta[t]) { tripEta[t] = eta; tripExit[t] = i; tripNext[t] = nx }
            }
            if ((f and 1) == 0 || tripEta[t] > arriveBy) continue
            val s = day.u[i]
            if (target[s]) continue
            val e = Entry(d, tripEta[t], i, tripExit[t], -1, tripNext[t])
            if (!insert(prof[s], e)) continue
            for (k in walk.start[s] until walk.start[s + 1]) {
                val w = walk.to[k]
                if (!target[w]) insert(prof[w], Entry(d - walk.dur[k], e.eta, -1, -1, s, e))
            }
        }
        val latest = IntArray(nNodes) { if (prof[it].isEmpty()) -1 else prof[it][0].dep }
        return Profile(latest, prof)
    }

    /** Úseky cesty ze zastávky [start] podle položky profilu. */
    fun legsFrom(day: Day, start: Int, entry: Entry?): List<Leg> {
        val legs = ArrayList<Leg>()
        var s = start; var e = entry; var g = 0
        while (e != null && g++ < 30) {
            if (e.walk >= 0) {
                legs.add(Leg.WalkLeg(s, e.walk)); s = e.walk; e = e.next; continue
            }
            val x = e.exit
            legs.add(Leg.Ride(day.trip[e.conn], s, e.dep, day.v[x], day.arr[x]))
            s = day.v[x]; e = e.next
        }
        return legs
    }

    /** Celá trasa jízdy se zastávkami a časy. */
    fun tripRoute(day: Day, t: Int): List<RouteStop> {
        val cs = ArrayList<Int>()
        for (i in 0 until day.n) if (day.trip[i] == t) cs.add(i)
        cs.sortWith(compareBy<Int>({ day.dep[it] }, { day.arr[it] }))
        val st = ArrayList<RouteStop>()
        cs.forEachIndexed { k, c ->
            if (k == 0) st.add(RouteStop(day.u[c], day.dep[c], day.dep[c])) else st.last().dep = day.dep[c]
            if (day.v[c] != st.last().node) st.add(RouteStop(day.v[c], day.arr[c], day.arr[c]))
        }
        return st
    }

    /** Index nástupní a výstupní zastávky úseku v trase jízdy. */
    fun legIndices(route: List<RouteStop>, leg: Leg.Ride): Pair<Int, Int> {
        var a = route.indexOfFirst { it.node == leg.from && it.dep == leg.dep }
        if (a < 0) a = route.indexOfFirst { it.node == leg.from }
        val from = a.coerceAtLeast(0)
        var b = -1
        for (k in from until route.size) if (route[k].node == leg.to && route[k].arr == leg.arr) { b = k; break }
        if (b < 0) for (k in from until route.size) if (route[k].node == leg.to) { b = k; break }
        return a to b
    }
}
