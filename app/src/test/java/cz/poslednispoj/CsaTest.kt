package cz.poslednispoj

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Kontrola výpočtu na aktuálních datech (první den v datech, návrat do Prahy do 19:00).
 * Ověřuje pravidla, která musí platit pro jakákoli data:
 *  - poslední odjezd = první položka profilu,
 *  - dřívější spoje mají dřívější odjezd i dřívější příjezd do cíle,
 *  - každá cesta navazuje, nikde nečeká záporně a do cíle dorazí nejpozději v 19:00.
 */
class CsaTest {
    private fun fetch(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try { if (c.responseCode == 200) c.inputStream.readBytes() else null } finally { c.disconnect() }
    }

    @Test
    fun profileIsConsistent() {
        val metaBytes = fetch(Config.DATA_URL + "meta.json")
        assumeTrue("data nejsou k dispozici", metaBytes != null)
        val o = JSONObject(String(metaBytes!!))
        val ttBytes = fetch(Config.DATA_URL + "timetable.bin")
        assumeTrue("jízdní řád není k dispozici", ttBytes != null)
        val t0 = System.currentTimeMillis()
        val tt = Timetable.parse(GZIPInputStream(ttBytes!!.inputStream()).readBytes())
        val t1 = System.currentTimeMillis()
        val nodes = o.getJSONArray("nodes")
        val n = nodes.length()
        val target = BooleanArray(n) { nodes.getJSONArray(it).getInt(3) == 1 }
        val change = IntArray(n) { if ((nodes.getJSONArray(it).getInt(4) and 4) != 0) 4 else 2 }
        val w = o.getJSONArray("walk")
        val walk = Csa.buildWalk(n, IntArray(w.length()) { w.getInt(it) })
        val day = tt.buildDay(0)
        val t2 = System.currentTimeMillis()
        val by = 19 * 60
        val p = Csa.profile(day, walk, n, target, by, change, null)
        println("jízd ${tt.nTrip}, spojů dne ${day.n}, čtení ${t1 - t0} ms, sestavení dne ${t2 - t1} ms, výpočet ${System.currentTimeMillis() - t2} ms")
        for (k in 1 until day.n) assertTrue("spoje nejsou seřazené", day.dep[k] <= day.dep[k - 1])

        val reach = p.latest.count { it >= 0 }
        println("dosažitelných zastávek: $reach")
        assertTrue("málo dosažitelných zastávek: $reach", reach > 10000)
        var checked = 0
        for (i in 0 until n) {
            val prof = p.prof[i]
            if (prof.isEmpty()) { assertEquals(-1, p.latest[i]); continue }
            assertEquals(prof[0].dep, p.latest[i])
            for (k in 1 until prof.size) {
                assertTrue("odjezdy nejsou sestupně u $i", prof[k].dep < prof[k - 1].dep)
                assertTrue("dřívější spoj nedorazí dřív u $i", prof[k].eta < prof[k - 1].eta)
            }
            if (i % 7 != 0) continue // cesty kontrolujeme u vzorku zastávek
            for (e in prof) {
                assertTrue(e.eta <= by)
                val legs = Csa.legsFrom(day, i, e)
                assertTrue(legs.isNotEmpty())
                var at = i; var t = e.dep
                for (l in legs) when (l) {
                    is Leg.WalkLeg -> { assertEquals(at, l.from); at = l.to }
                    is Leg.Ride -> {
                        assertEquals(at, l.from)
                        assertTrue("jízda odjíždí dřív, než tam jsem", l.dep >= t)
                        assertTrue(l.arr >= l.dep)
                        at = l.to; t = l.arr
                    }
                }
                assertTrue("cesta nekončí v cíli", target[at])
                assertTrue("příjezd po 19:00", t <= by)
                checked++
            }
        }
        assertTrue(checked > 1000)
    }
}
