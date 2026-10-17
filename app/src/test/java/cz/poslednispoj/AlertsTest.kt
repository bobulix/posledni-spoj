package cz.poslednispoj

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Stáhne živé mimořádnosti PID a ověří, že se dají přečíst (výpis je v test-results). */
class AlertsTest {
    @Test
    fun parsesLiveAlerts() {
        val list = try { Alerts.fetch() } catch (e: Exception) { assumeTrue("mimořádnosti nejsou dostupné: ${e.message}", false); return }
        println("mimořádností: ${list.size}")
        list.take(8).forEach { println("- ${it.header} | linky ${it.routeIds.take(6)} | ${it.url} | období ${it.periods.size}") }
        if (list.isNotEmpty()) {
            assertTrue("žádná mimořádnost nemá text", list.any { it.header.isNotEmpty() })
            assertTrue("žádná mimořádnost nemá linku", list.any { it.routeIds.isNotEmpty() })
        }
    }
}
