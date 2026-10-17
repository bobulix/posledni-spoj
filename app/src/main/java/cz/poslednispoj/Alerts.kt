package cz.poslednispoj

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Jedna mimořádnost PID (z GTFS-RT Service Alerts). Časy jsou v sekundách od 1. 1. 1970. */
class Alert(
    val id: String,
    val header: String,
    val description: String,
    val url: String,
    val routeIds: Set<String>,
    val periods: List<LongArray>, // [start, end], 0 = neomezeno
) {
    /** Platí mimořádnost někdy v intervalu [from, to)? */
    fun activeIn(from: Long, to: Long): Boolean =
        periods.isEmpty() || periods.any { (s, e) -> (s == 0L || s < to) && (e == 0L || e > from) }
}

/** Živé zprávy o mimořádnostech PID (Golemio, bez registrace). */
object Alerts {
    const val URL_PID = "https://api.golemio.cz/v2/vehiclepositions/gtfsrt/alerts.pb"

    fun fetch(): List<Alert> {
        val c = URL(URL_PID).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "PosledniSpoj (Android)")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return parse(c.inputStream.use { it.readBytes() })
        } finally { c.disconnect() }
    }

    // ---- minimální čtečka protobufu (jen to, co GTFS-RT Alerts potřebují) ----
    private class Pb(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x < 0x80) return r
                s += 7
            }
        }
        /** Přečte klíč pole; vrací (číslo pole, typ). */
        fun key(): Pair<Int, Int> { val k = varint(); return (k ushr 3).toInt() to (k and 7).toInt() }
        fun sub(): Pb { val len = varint().toInt(); val s = Pb(b, p, p + len); p += len; return s }
        fun string(): String { val len = varint().toInt(); val s = String(b, p, len, Charsets.UTF_8); p += len; return s }
        fun skip(type: Int) {
            when (type) {
                0 -> varint()
                1 -> p += 8
                2 -> { val len = varint().toInt(); p += len }
                5 -> p += 4
                else -> throw IOException("neznámý typ $type")
            }
        }
    }

    fun parse(bytes: ByteArray): List<Alert> {
        val out = ArrayList<Alert>()
        val msg = Pb(bytes, 0, bytes.size)
        while (msg.more()) {
            val (f, t) = msg.key()
            if (f == 2 && t == 2) parseEntity(msg.sub())?.let { out.add(it) } else msg.skip(t)
        }
        return out
    }

    private fun parseEntity(e: Pb): Alert? {
        var id = ""; var alert: Alert? = null; var deleted = false
        while (e.more()) {
            val (f, t) = e.key()
            when {
                f == 1 && t == 2 -> id = e.string()
                f == 2 && t == 0 -> deleted = e.varint() != 0L
                f == 5 && t == 2 -> alert = parseAlert(id, e.sub())
                else -> e.skip(t)
            }
        }
        return if (deleted) null else alert
    }

    private fun parseAlert(id: String, a: Pb): Alert {
        val routes = HashSet<String>(); val periods = ArrayList<LongArray>()
        var header = ""; var desc = ""; var url = ""
        while (a.more()) {
            val (f, t) = a.key()
            when {
                f == 1 && t == 2 -> {
                    val r = a.sub(); val pr = LongArray(2)
                    while (r.more()) { val (g, u) = r.key(); if (g in 1..2 && u == 0) pr[g - 1] = r.varint() else r.skip(u) }
                    periods.add(pr)
                }
                f == 5 && t == 2 -> {
                    val s = a.sub()
                    while (s.more()) { val (g, u) = s.key(); if (g == 2 && u == 2) routes.add(s.string()) else s.skip(u) }
                }
                f == 8 && t == 2 -> url = translated(a.sub())
                f == 10 && t == 2 -> header = translated(a.sub())
                f == 11 && t == 2 -> desc = translated(a.sub())
                else -> a.skip(t)
            }
        }
        return Alert(id, header.trim(), desc.trim(), url.trim(), routes, periods)
    }

    /** TranslatedString: přednostně česky, jinak první překlad. */
    private fun translated(s: Pb): String {
        var first: String? = null; var cs: String? = null
        while (s.more()) {
            val (f, t) = s.key()
            if (f == 1 && t == 2) {
                val tr = s.sub(); var text = ""; var lang = ""
                while (tr.more()) {
                    val (g, u) = tr.key()
                    when { g == 1 && u == 2 -> text = tr.string(); g == 2 && u == 2 -> lang = tr.string(); else -> tr.skip(u) }
                }
                if (first == null) first = text
                if (lang.startsWith("cs") && cs == null) cs = text
            } else s.skip(t)
        }
        return cs ?: first ?: ""
    }
}
