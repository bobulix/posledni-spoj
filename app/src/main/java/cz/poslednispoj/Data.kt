package cz.poslednispoj

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Config {
    /** Odkud se stahují data připravená noční úlohou na GitHubu (větev "data"). */
    const val DATA_URL = "https://raw.githubusercontent.com/bobulix/posledni-spoj/data/"
}

/** Zastávky, linky, pěší přechody a seznam dnů, pro které jsou jízdní řády. */
class Meta(
    val names: Array<String>,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val praha: BooleanArray,
    val modes: IntArray,          // 1 tram, 2 metro, 4 vlak, 8 bus, 16 jiné
    val routeName: Array<String>,
    val routeType: IntArray,      // GTFS route_type
    val routeColor: IntArray,     // ARGB
    val walkFlat: IntArray,
    val days: List<Pair<String, String>>, // (YYYYMMDD, den v týdnu anglicky)
    val generated: String,
) {
    val size get() = names.size
}

class TripInfo(val route: IntArray, val head: Array<String>, val shape: IntArray)

/** Skutečné trasy linek (encoded polyline, přesnost 1e-5), dekódují se až při použití. */
class Shapes(private val enc: Array<String>) {
    private val cache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()
    fun get(i: Int): Pair<DoubleArray, DoubleArray>? {
        if (i < 0 || i >= enc.size) return null
        return cache.getOrPut(i) { decode(enc[i]) }
    }
    private fun decode(s: String): Pair<DoubleArray, DoubleArray> {
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        var idx = 0; var lat = 0; var lon = 0
        fun next(): Int {
            var res = 0; var shift = 0; var b: Int
            do { b = s[idx++].code - 63; res = res or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            return if ((res and 1) != 0) (res shr 1).inv() else res shr 1
        }
        while (idx < s.length) { lat += next(); lon += next(); la.add(lat / 1e5); lo.add(lon / 1e5) }
        return la.toDoubleArray() to lo.toDoubleArray()
    }
}

class DataRepo(private val ctx: Context) {
    private val root = File(ctx.filesDir, "data")

    suspend fun loadMeta(): Meta = withContext(Dispatchers.IO) {
        root.mkdirs()
        val f = File(root, "meta.json")
        try {
            download(Config.DATA_URL + "meta.json", f)
        } catch (e: Exception) {
            if (!f.exists()) throw e // bez signálu použijeme naposledy stažená data
        }
        parseMeta(f.readText())
    }

    suspend fun loadDay(meta: Meta, day: String): Pair<Day, TripInfo> = withContext(Dispatchers.IO) {
        val dir = File(root, meta.generated)
        dir.mkdirs()
        // starší sady dat smažeme
        root.listFiles()?.forEach { if (it.isDirectory && it.name != meta.generated) it.deleteRecursively() }
        val cf = File(dir, "conn_$day.bin")
        val tf = File(dir, "trips_$day.json")
        if (!cf.exists()) download(Config.DATA_URL + "conn_$day.bin", cf)
        if (!tf.exists()) download(Config.DATA_URL + "trips_$day.json", tf)
        val bytes = GZIPInputStream(cf.inputStream().buffered()).use { it.readBytes() }
        val d = Csa.parseDay(bytes)
        val ja = JSONArray(tf.readText())
        val route = IntArray(ja.length()) { ja.getJSONArray(it).getInt(0) }
        val head = Array(ja.length()) { ja.getJSONArray(it).optString(1, "") }
        val shape = IntArray(ja.length()) { ja.getJSONArray(it).optInt(2, -1) }
        d to TripInfo(route, head, shape)
    }

    /** Tvary tras; když se nepodaří stáhnout, vrací null (cesta se pak kreslí spojnicemi zastávek). */
    suspend fun loadShapes(meta: Meta): Shapes? = withContext(Dispatchers.IO) {
        try {
            val dir = File(root, meta.generated); dir.mkdirs()
            val f = File(dir, "shapes.bin")
            if (!f.exists()) download(Config.DATA_URL + "shapes.bin", f)
            val text = GZIPInputStream(f.inputStream().buffered()).use { String(it.readBytes()) }
            val ja = JSONArray(text)
            Shapes(Array(ja.length()) { ja.getString(it) })
        } catch (e: Exception) { null }
    }

    private fun download(url: String, f: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "PosledniSpoj/0.1 (Android)")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} pro $url")
            val tmp = File(f.path + ".tmp")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseMeta(text: String): Meta {
        val o = JSONObject(text)
        val nodes = o.getJSONArray("nodes")
        val n = nodes.length()
        val names = Array(n) { "" }
        val lat = DoubleArray(n); val lon = DoubleArray(n)
        val praha = BooleanArray(n); val modes = IntArray(n)
        for (i in 0 until n) {
            val a = nodes.getJSONArray(i)
            names[i] = a.getString(0); lat[i] = a.getDouble(1); lon[i] = a.getDouble(2)
            praha[i] = a.getInt(3) == 1; modes[i] = a.getInt(4)
        }
        val routes = o.getJSONArray("routes")
        val rn = routes.length()
        val rName = Array(rn) { "" }; val rType = IntArray(rn); val rCol = IntArray(rn)
        for (i in 0 until rn) {
            val a = routes.getJSONArray(i)
            rName[i] = a.getString(0); rType[i] = a.getInt(1)
            rCol[i] = try { (0xFF000000.toInt()) or a.getString(2).toInt(16) } catch (e: Exception) { 0xFF888888.toInt() }
        }
        val w = o.getJSONArray("walk")
        val walk = IntArray(w.length()) { w.getInt(it) }
        val dj = o.getJSONArray("days")
        val days = (0 until dj.length()).map { dj.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } }
        return Meta(names, lat, lon, praha, modes, rName, rType, rCol, walk, days, o.optString("generated", "v0"))
    }
}
