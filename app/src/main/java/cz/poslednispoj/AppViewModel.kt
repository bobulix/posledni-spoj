package cz.poslednispoj

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val PRAHA = "Praha (kamkoli do města)"

/** Čára na mapě (zeměpisné souřadnice). */
class GeoPath(val walk: Boolean, val lat: DoubleArray, val lon: DoubleArray)

/** Pásma barev podle toho, kolik hodin před příjezdem je poslední odjezd. */
class Bin(val hours: Int, val bg: Int, val fg: Int)

val BINS = listOf(
    Bin(1, 0xFF1E7B4F.toInt(), 0xFFFFFFFF.toInt()),
    Bin(2, 0xFF4E9A35.toInt(), 0xFFFFFFFF.toInt()),
    Bin(3, 0xFFA4A923.toInt(), 0xFF1C2620.toInt()),
    Bin(4, 0xFFE0A019.toInt(), 0xFF1C2620.toInt()),
    Bin(6, 0xFFDD6A1E.toInt(), 0xFFFFFFFF.toInt()),
    Bin(99, 0xFFB23A2A.toInt(), 0xFFFFFFFF.toInt()),
)

fun hm(m: Int): String = if (m < 0) "—" else "${(m / 60) % 24}:${(m % 60).toString().padStart(2, '0')}"

/** Druhy dopravy: bit masky pro filtr. */
object Modes {
    const val TRAIN = 1; const val BUS = 2; const val TRAM = 4; const val METRO = 8; const val OTHER = 16
    fun ofRouteType(t: Int) = when (t) { 2 -> TRAIN; 3, 11 -> BUS; 0 -> TRAM; 1 -> METRO; else -> OTHER }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DataRepo(app)
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // ---- data ----
    var meta: Meta? = null
        private set
    var day: Day? = null
        private set
    var trips: TripInfo? = null
        private set
    var walk: Walk? = null
        private set
    var change: IntArray = IntArray(0)
        private set
    var nameIdx: Map<String, List<Int>> = emptyMap()
        private set
    var sortedNames: List<String> = emptyList()
        private set
    var tripMode: IntArray = IntArray(0)
        private set
    var importance: IntArray = IntArray(0)
        private set

    // ---- výsledek ----
    var profile: Profile? = null
        private set
    var target: BooleanArray = BooleanArray(0)
        private set
    var nowMin by mutableIntStateOf(-1)
        private set

    // ---- stav UI ----
    var loading by mutableStateOf<String?>("Načítám jízdní řády…")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var dest by mutableStateOf(PRAHA)
        private set
    var arriveBy by mutableIntStateOf(19 * 60)
        private set
    var dayKey by mutableStateOf("")
        private set
    var modeMask by mutableIntStateOf(prefs.getInt("modes", 15))
        private set
    var panelCollapsed by mutableStateOf(prefs.getBoolean("collapsed", false))
        private set
    var opacity by mutableStateOf(prefs.getFloat("opacity", 1f))
        private set
    var selected by mutableIntStateOf(-1)
        private set
    var selK by mutableIntStateOf(0)
        private set
    var sheetMini by mutableStateOf(false)
        private set
    val expanded = mutableStateMapOf<Int, IntArray>() // úsek -> uzly celé trasy spoje
    var journey: List<Leg> = emptyList()
        private set
    var journeyPaths: List<GeoPath> = emptyList() // úseky cesty po skutečné trase
        private set
    /** Rozbalené celé trasy spojů jako čáry (podle skutečné trasy, je-li k dispozici). */
    var expandedLines: Map<Int, GeoPath> = emptyMap()
        private set
    private var shapes: Shapes? = null

    /** Zvyšuje se při každé změně, kterou má překreslit mapová vrstva. */
    var renderTick by mutableIntStateOf(0)
        private set

    private var computeJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            loading = "Načítám jízdní řády…"
            val m = withRetry { repo.loadMeta() }
            withContext(Dispatchers.Default) {
                walk = Csa.buildWalk(m.size, m.walkFlat)
                change = IntArray(m.size) { if ((m.modes[it] and 4) != 0) 4 else 2 }
                val idx = HashMap<String, MutableList<Int>>()
                for (i in 0 until m.size) idx.getOrPut(m.names[i]) { ArrayList() }.add(i)
                nameIdx = idx
                val coll = java.text.Collator.getInstance(Locale("cs", "CZ"))
                sortedNames = idx.keys.sortedWith(coll)
                searchIndex = sortedNames.map { it to tokens(it) }
            }
            meta = m
            val today = todayKey()
            dayKey = m.days.firstOrNull { it.first == today }?.first
                ?: m.days.firstOrNull { it.first > today }?.first
                ?: m.days.first().first
            loadDayAndCompute()
        } catch (e: Exception) {
            error = "Data se nepodařilo načíst. Zkontroluj připojení k internetu. (${e.message})"
            loading = null
        }
    }

    private suspend fun loadDayAndCompute() {
        val m = meta ?: return
        try {
            loading = "Stahuji jízdní řády na ${dayLabel(dayKey)}…"
            val (d, t) = withRetry { repo.loadDay(m, dayKey) }
            withContext(Dispatchers.Default) {
                tripMode = IntArray(d.nTrips) { if (it < t.route.size) Modes.ofRouteType(m.routeType[t.route[it]]) else Modes.OTHER }
                val imp = IntArray(m.size)
                for (i in 0 until d.n) imp[d.u[i]]++
                importance = imp
            }
            day = d; trips = t
            loading = null; error = null
            if (shapes == null) viewModelScope.launch { shapes = repo.loadShapes(m) }
            recompute()
        } catch (e: Exception) {
            error = "Jízdní řády se nepodařilo stáhnout. (${e.message})"
            loading = null
        }
    }

    fun recompute() {
        val m = meta ?: return
        val d = day ?: return
        val w = walk ?: return
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            val tgt = BooleanArray(m.size)
            val label: String
            if (dest == PRAHA || dest.equals("praha", true)) {
                for (i in 0 until m.size) tgt[i] = m.praha[i]; label = "Prahy"
            } else {
                val ids = nameIdx[dest]
                if (ids == null) { status = "Zastávku „$dest“ neznám. Vyber ji ze seznamu."; return@launch }
                ids.forEach { tgt[it] = true }; label = dest
            }
            if ((modeMask and 15) == 0) {
                profile = null; status = "Zapni aspoň jeden způsob dopravy."; renderTick++; return@launch
            }
            status = "Počítám…"
            val mask = modeMask or Modes.OTHER
            val by = arriveBy
            val t0 = System.currentTimeMillis()
            val p = withContext(Dispatchers.Default) {
                val allowed = BooleanArray(d.nTrips) { (tripMode[it] and mask) != 0 }
                Csa.profile(d, w, m.size, tgt, by, change, allowed)
            }
            val ms = System.currentTimeMillis() - t0
            target = tgt; profile = p
            nowMin = if (dayKey == todayKey()) Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } else -1
            val c = p.latest.count { it >= 0 }
            status = "$c zastávek, odkud se dostaneš do $label do ${hm(by)} · výpočet $ms ms"
            if (selected >= 0) select(selected, 0) else renderTick++
        }
    }

    // ---- akce z UI ----
    fun setDestination(name: String) { dest = name; closeSheet(); recompute() }
    fun setArrival(min: Int) { arriveBy = min; recompute() }
    fun setDay(key: String) {
        if (key == dayKey) return
        dayKey = key; closeSheet()
        viewModelScope.launch { loadDayAndCompute() }
    }
    fun toggleMode(bit: Int) {
        modeMask = modeMask xor bit
        prefs.edit().putInt("modes", modeMask).apply()
        closeSheet(); recompute()
    }
    fun togglePanel() { panelCollapsed = !panelCollapsed; prefs.edit().putBoolean("collapsed", panelCollapsed).apply() }
    fun updateOpacity(v: Float) { opacity = v; prefs.edit().putFloat("opacity", v).apply(); renderTick++ }
    fun retry() { error = null; viewModelScope.launch { if (meta == null) load() else loadDayAndCompute() } }

    fun select(i: Int, k: Int = 0) {
        val p = profile ?: return
        val d = day ?: return
        selected = i; selK = k
        expanded.clear()
        val e = p.prof[i].getOrNull(k)
        journey = if (e != null) Csa.legsFrom(d, i, e) else emptyList()
        val m = meta ?: return
        journeyPaths = journey.map { l ->
            when (l) {
                is Leg.WalkLeg -> pathOfNodes(m, true, intArrayOf(l.from, l.to))
                is Leg.Ride -> {
                    val cut = shapeSegment(m, l.trip, l.from, l.to)
                    if (cut != null) cut else {
                        val r = Csa.tripRoute(d, l.trip)
                        val (a, b) = Csa.legIndices(r, l)
                        pathOfNodes(m, false, if (a >= 0 && b >= a) IntArray(b - a + 1) { r[a + it].node } else intArrayOf(l.from, l.to))
                    }
                }
            }
        }
        expandedLines = emptyMap()
        renderTick++
    }
    fun closeSheet() { selected = -1; journey = emptyList(); journeyPaths = emptyList(); expanded.clear(); expandedLines = emptyMap(); renderTick++ }

    private fun pathOfNodes(m: Meta, walk: Boolean, nodes: IntArray) =
        GeoPath(walk, DoubleArray(nodes.size) { m.lat[nodes[it]] }, DoubleArray(nodes.size) { m.lon[nodes[it]] })

    /** Výřez skutečné trasy jízdy mezi dvěma zastávkami (nejbližší body trasy). */
    private fun shapeSegment(m: Meta, trip: Int, from: Int, to: Int): GeoPath? {
        val si = trips?.shape?.getOrNull(trip) ?: return null
        val (la, lo) = shapes?.get(si) ?: return null
        if (la.size < 2) return null
        fun nearest(node: Int, start: Int): Int {
            var best = start; var bd = Double.MAX_VALUE
            val cl = Math.cos(Math.toRadians(m.lat[node]))
            for (k in start until la.size) {
                val dy = la[k] - m.lat[node]; val dx = (lo[k] - m.lon[node]) * cl
                val dd = dx * dx + dy * dy
                if (dd < bd) { bd = dd; best = k }
            }
            return best
        }
        val a = nearest(from, 0)
        val b = nearest(to, a)
        if (b <= a) return null
        val n = b - a + 1
        return GeoPath(false,
            DoubleArray(n + 2) { when (it) { 0 -> m.lat[from]; n + 1 -> m.lat[to]; else -> la[a + it - 1] } },
            DoubleArray(n + 2) { when (it) { 0 -> m.lon[from]; n + 1 -> m.lon[to]; else -> lo[a + it - 1] } })
    }
    fun toggleMini() { sheetMini = !sheetMini }
    fun expandSheet() { sheetMini = false }
    fun toggleRoute(legIndex: Int) {
        if (expanded.containsKey(legIndex)) expanded.remove(legIndex)
        else {
            val l = journey.getOrNull(legIndex) as? Leg.Ride ?: return
            val d = day ?: return
            val nodes = Csa.tripRoute(d, l.trip).map { it.node }.toIntArray()
            expanded[legIndex] = nodes
        }
        val m = meta
        expandedLines = if (m == null) emptyMap() else expanded.mapValues { (li, nodes) ->
            val l = journey.getOrNull(li) as? Leg.Ride
            val si = if (l != null) trips?.shape?.getOrNull(l.trip) ?: -1 else -1
            val sh = shapes?.get(si)
            if (sh != null && sh.first.size >= 2) GeoPath(false, sh.first, sh.second) else pathOfNodes(m, false, nodes)
        }
        renderTick++
    }
    fun returnHere() { val m = meta ?: return; if (selected >= 0) setDestination(m.names[selected]) }

    // ---- pomocné ----
    fun binOf(dep: Int): Bin {
        val h = (arriveBy - dep) / 60.0
        return BINS.firstOrNull { h <= it.hours } ?: BINS.last()
    }

    fun dayLabel(key: String): String {
        if (key.length != 8) return key
        val m = meta
        val wd = m?.days?.firstOrNull { it.first == key }?.second
        val short = when (wd) {
            "monday" -> "Po"; "tuesday" -> "Út"; "wednesday" -> "St"; "thursday" -> "Čt"
            "friday" -> "Pá"; "saturday" -> "So"; "sunday" -> "Ne"; else -> ""
        }
        val today = if (key == todayKey()) " (dnes)" else ""
        return "$short ${key.substring(6).toInt()}. ${key.substring(4, 6).toInt()}.$today"
    }

    /** Při výpadku sítě to zkusí znovu (po 2, 5 a 10 s), teprve pak ukáže chybu. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        val waits = longArrayOf(2000, 5000, 10000)
        for (w in waits) {
            try { return block() } catch (e: Exception) { kotlinx.coroutines.delay(w) }
        }
        return block()
    }

    // ---- našeptávač cíle ----
    private var searchIndex: List<Pair<String, List<String>>> = emptyList()

    /** Malá písmena bez diakritiky. */
    private fun norm(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    private fun tokens(s: String): List<String> = norm(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Návrhy cílů: každé napsané slovo musí být začátkem některého slova v názvu, ve stejném pořadí
     * („kr vr“ → Kropáčova Vrutice, „pr ho“ → Praha-Hostivař), bez ohledu na diakritiku.
     * Když nic takového není, hledá se výskyt textu kdekoli v názvu.
     */
    /** Vlaková stanice/zastávka (některý uzel s tímto názvem obsluhuje vlak). */
    private fun isStation(name: String): Boolean {
        val m = meta ?: return false
        return nameIdx[name]?.any { (m.modes[it] and 4) != 0 } == true
    }

    fun suggest(query: String, limit: Int = 8): List<String> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        // skóre = součet pořadí slov, na která se trefila napsaná slova (nižší = lepší), -1 = neshoda
        fun score(t: List<String>): Int {
            var j = 0; var sc = 0
            for ((k, w) in t.withIndex()) { if (j < q.size && w.startsWith(q[j])) { sc += k; j++ } }
            return if (j == q.size) sc else -1
        }
        val res = ArrayList<String>()
        if (score(tokens(PRAHA)) >= 0) res.add(PRAHA)
        val hits = searchIndex.map { it.first to score(it.second) }.filter { it.second >= 0 }
            .sortedWith(compareBy<Pair<String, Int>>({ it.second }, { if (isStation(it.first)) 0 else 1 }, { it.first.length }))
        hits.take(limit).forEach { res.add(it.first) }
        if (res.size < limit) {
            val nq = norm(query.trim())
            for ((name, _) in searchIndex) {
                if (res.size >= limit) break
                if (name !in res && norm(name).contains(nq)) res.add(name)
            }
        }
        return res.take(limit)
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
}
