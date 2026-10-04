package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerThermalGuardTest {
    private fun feed(g: ChargerThermalGuard, from: Long, steps: Int, startC: Float, perStepC: Float, watts: Float?): ChargerThermalGuard.Advice? {
        var last: ChargerThermalGuard.Advice? = null
        for (i in 0 until steps) {
            val a = g.onSample(from + i * 10_000L, true, startC + perStepC * i, watts, 50)
            if (a != null) last = a
        }
        return last
    }

    @Test fun missingDataGivesNothing() {
        val g = ChargerThermalGuard()
        assertNull(g.onSample(0L, true, null, 10f))
        assertNull(g.onSample(0L, null, 36f, 10f))
    }
    @Test fun notChargingNeverAdvises() {
        val g = ChargerThermalGuard()
        for (i in 0 until 30) assertNull(g.onSample(i * 10_000L, false, 36f + i, 10f))
    }
    @Test fun stableWarmPhoneIsQuiet() {
        val g = ChargerThermalGuard()
        assertNull(feed(g, 0L, 30, 36f, 0f, 2f))
    }
    @Test fun earlyRiseSpeaksBeforeTheFortyDegreeWarning() {
        val g = ChargerThermalGuard()
        // +0.05 C per 10 s = 0.3 C per minute, from 35.0
        val a = feed(g, 0L, 12, 35.0f, 0.05f, 2f)
        assertEquals(ChargerThermalGuard.Advice.EARLY_RISE, a)
    }
    @Test fun preDangerNearThirtyEight() {
        val g = ChargerThermalGuard()
        val a = feed(g, 0L, 12, 38.0f, 0.04f, 2f)
        assertEquals(ChargerThermalGuard.Advice.PRE_DANGER, a)
    }
    @Test fun aboveExistingWarningStaysWithExistingEvents() {
        val g = ChargerThermalGuard()
        assertNull(feed(g, 0L, 12, 40.5f, 0.05f, 2f))
    }
    @Test fun cooldownStopsRepeats() {
        val g = ChargerThermalGuard()
        var count = 0
        for (i in 0 until 40) if (g.onSample(i * 10_000L, true, 35.0f + 0.05f * i.coerceAtMost(30), 2f, 50) == ChargerThermalGuard.Advice.EARLY_RISE) count++
        assertTrue(count in 1..2)
    }
    @Test fun slowChargingPlusHeatSuspectsCharger() {
        val g = ChargerThermalGuard()
        // slow (2 W) for 6 minutes while warming from 36.0 to 38.0
        var got: ChargerThermalGuard.Advice? = null
        for (i in 0 until 40) {
            val a = g.onSample(i * 10_000L, true, 36.0f + 2.0f * i / 39f, 2f, 50)
            if (a == ChargerThermalGuard.Advice.CHARGER_SUSPECT) got = a
        }
        assertEquals(ChargerThermalGuard.Advice.CHARGER_SUSPECT, got)
    }
    @Test fun slowButCoolIsNotSuspect() {
        val g = ChargerThermalGuard()
        for (i in 0 until 60) assertNull(g.onSample(i * 10_000L, true, 30f, 2f))
    }
    @Test fun unplugResets() {
        val g = ChargerThermalGuard()
        feed(g, 0L, 12, 35.0f, 0.05f, 2f)
        g.onSample(200_000L, false, 30f, null)
        assertNull(g.slopeCPerMin())
    }
    @Test fun textsNeverClaimProof() {
        val t = ChargerThermalGuard().text(ChargerThermalGuard.Advice.CHARGER_SUSPECT, 38.2f)
        assertTrue(t.contains("may be faulty"))
        assertTrue(t.contains("38.2"))
    }

    private fun suspectRun(g: ChargerThermalGuard, level: Int?, firstWatts: Float = 2f): ChargerThermalGuard.Advice? {
        var got: ChargerThermalGuard.Advice? = null
        for (i in 0 until 40) {
            val w = if (i == 0) firstWatts else 2f
            val a = g.onSample(i * 10_000L, true, 36.0f + 2.0f * i / 39f, w, level)
            if (a == ChargerThermalGuard.Advice.CHARGER_SUSPECT) got = a
        }
        return got
    }
    @Test fun nearFullTaperNeverSuspectsCharger() {
        assertNull(suspectRun(ChargerThermalGuard(), 100))
        assertNull(suspectRun(ChargerThermalGuard(), 95))
    }
    @Test fun unknownLevelStaysQuiet() {
        assertNull(suspectRun(ChargerThermalGuard(), null))
    }
    @Test fun fastCapableChargerStaysQuiet() {
        assertNull(suspectRun(ChargerThermalGuard(), 50, firstWatts = 12f))
    }
    @Test fun justBelowNearFullStillSuspects() {
        assertEquals(ChargerThermalGuard.Advice.CHARGER_SUSPECT, suspectRun(ChargerThermalGuard(), 94))
    }

    @Test fun heatingOnAFastChargerStaysQuiet() {
        assertNull(feed(ChargerThermalGuard(), 0L, 12, 35.0f, 0.05f, 12f))
        assertNull(feed(ChargerThermalGuard(), 0L, 12, 38.0f, 0.04f, 12f))
    }
    @Test fun slowChargingWithFallingOrFlatTemperatureStaysQuiet() {
        assertNull(feed(ChargerThermalGuard(), 0L, 40, 39f, -0.05f, 2f))
        assertNull(feed(ChargerThermalGuard(), 0L, 40, 36f, 0f, 2f))
    }
    @Test fun nearFullHeatingStaysQuiet() {
        val g = ChargerThermalGuard()
        var a: ChargerThermalGuard.Advice? = null
        for (i in 0 until 12) a = g.onSample(i * 10_000L, true, 35.0f + 0.05f * i, 2f, 100) ?: a
        assertNull(a)
    }
    @Test fun slowUsbPortHeatSaysUsbNotFaultyCharger() {
        val g = ChargerThermalGuard()
        var got: ChargerThermalGuard.Advice? = null
        for (i in 0 until 40) {
            val a = g.onSample(i * 10_000L, true, 36.0f + 2.0f * i / 39f, 2f, 50, true)
            if (a == ChargerThermalGuard.Advice.USB_PORT_HEAT || a == ChargerThermalGuard.Advice.CHARGER_SUSPECT) got = a
        }
        assertEquals(ChargerThermalGuard.Advice.USB_PORT_HEAT, got)
        assertTrue(ChargerThermalGuard().text(ChargerThermalGuard.Advice.USB_PORT_HEAT, 38f).contains("may not be the charger"))
    }
}
