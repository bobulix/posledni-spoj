package cz.poslednispoj

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Porovná výpočet s ověřeným webovým prototypem na datech ze soboty 17. 10. 2026
 * (návrat do Prahy do 19:00, všechny druhy dopravy). Bez dat (jiný den) se test přeskočí.
 */
class CsaTest {
    private fun fetch(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try { if (c.responseCode == 200) c.inputStream.readBytes() else null } finally { c.disconnect() }
    }

    @Test
    fun matchesPrototype() {
        val metaBytes = fetch(Config.DATA_URL + "meta.json")
        val conn = fetch(Config.DATA_URL + "conn_20261017.bin")
        assumeTrue("data pro 17. 10. už nejsou k dispozici", metaBytes != null && conn != null)
        val o = JSONObject(String(metaBytes!!))
        val nodes = o.getJSONArray("nodes")
        val n = nodes.length()
        val target = BooleanArray(n) { nodes.getJSONArray(it).getInt(3) == 1 }
        val change = IntArray(n) { if ((nodes.getJSONArray(it).getInt(4) and 4) != 0) 4 else 2 }
        val w = o.getJSONArray("walk")
        val walk = Csa.buildWalk(n, IntArray(w.length()) { w.getInt(it) })
        val day = Csa.parseDay(GZIPInputStream(conn!!.inputStream()).readBytes())
        val p = Csa.profile(day, walk, n, target, 19 * 60, change, null)
        var reach = 0; var tot = 0; var sumL = 0L
        for (i in 0 until n) { if (p.latest[i] >= 0) { reach++; sumL += p.latest[i] }; tot += p.prof[i].size }
        assertEquals(4983, reach)
        assertEquals(60107, tot)
        assertEquals(5013370L, sumL)
        assertEquals(1106, p.latest[1848]) // Karlštejn, žel. st. – 18:26
        assertEquals(46, p.prof[1848].size)
        val legs = Csa.legsFrom(day, 1848, p.prof[1848][0])
        assertEquals(1, legs.size)
    }
}
