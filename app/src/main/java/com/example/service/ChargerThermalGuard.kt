package com.example.service

/**
 * Early, proactive temperature advice while charging, plus a "charger or cable may be faulty" hint.
 *
 * Honest limits: an app cannot cool the phone or change the charger. This only watches the battery temperature and
 * charging power the phone reports, and gives warnings and steps for the user. It cannot prove a charger is faulty.
 * It acts on the temperature TREND, so it speaks before the existing 40 and 45 degree thresholds are reached.
 */
class ChargerThermalGuard {

    enum class Advice { EARLY_RISE, PRE_DANGER, CHARGER_SUSPECT, USB_PORT_HEAT }

    companion object {
        const val SAMPLE_GAP_MS = 10_000L           // keep at most one sample every 10 s
        const val WINDOW_MS = 5 * 60_000L           // trend is measured over the last 5 minutes
        const val MIN_SPAN_MS = 90_000L             // need at least 90 s of samples before judging a trend
        const val WARM_C = 35.0f                    // early advice starts from here, only when rising
        const val EARLY_SLOPE_C_PER_MIN = 0.20f
        const val PRE_DANGER_C = 38.0f              // below the existing 40 degree warning, so no overlap
        const val PRE_DANGER_SLOPE_C_PER_MIN = 0.15f
        const val EXISTING_WARNING_C = 40.0f        // at and above this the existing thermal events speak
        const val SLOW_WATTS = 5.0f                 // below this the app already calls it slow charging
        const val SLOW_FOR_MS = 5 * 60_000L         // slow for this long ...
        const val SUSPECT_MIN_C = 37.0f             // ... while the phone is warm ...
        const val SUSPECT_RISE_C = 1.5f             // ... and warmer than when the slow charging began
        const val NEAR_FULL_PERCENT = 95            // at or above this the phone tapers the current on purpose
        const val FAST_WATTS = 10.0f                // once this much power was seen, the charger is fast-capable
        const val COOLDOWN_MS = 3 * 60_000L         // repeat the same advice at most every 3 minutes
    }

    private data class Sample(val t: Long, val c: Float)

    private val samples = ArrayDeque<Sample>()
    private var slowSince: Long? = null
    private var slowStartC: Float? = null
    private var maxWattsSeen = 0f
    private val lastSpoken = HashMap<Advice, Long>()

    /** Forget everything, for example when the charger is unplugged. */
    fun reset() {
        samples.clear(); slowSince = null; slowStartC = null; maxWattsSeen = 0f; lastSpoken.clear()
    }

    /** Degrees per minute over the stored window, or null when there is too little data. */
    fun slopeCPerMin(): Float? {
        if (samples.size < 2) return null
        val first = samples.first(); val last = samples.last()
        val span = last.t - first.t
        if (span < MIN_SPAN_MS) return null
        return (last.c - first.c) / (span / 60_000f)
    }

    /**
     * Feed one reading. Returns advice to speak, or null. Missing data gives null, never a guess.
     * @param powerWatts charging power the phone reports; null when unavailable
     * @param levelPercent battery level; the charger-suspect hint needs it (unknown level means quiet)
     */
    fun onSample(nowMs: Long, charging: Boolean?, tempC: Float?, powerWatts: Float?, levelPercent: Int? = null, viaUsbPort: Boolean? = null): Advice? {
        if (charging != true || tempC == null) { if (charging == false) reset(); return null }
        if (samples.isEmpty() || nowMs - samples.last().t >= SAMPLE_GAP_MS) samples.addLast(Sample(nowMs, tempC))
        while (samples.isNotEmpty() && nowMs - samples.first().t > WINDOW_MS) samples.removeFirst()

        if (powerWatts != null && powerWatts > maxWattsSeen) maxWattsSeen = powerWatts

        // slow-charging timer
        if (powerWatts != null && powerWatts < SLOW_WATTS) {
            if (slowSince == null) { slowSince = nowMs; slowStartC = tempC }
        } else { slowSince = null; slowStartC = null }

        val slope = slopeCPerMin()
        // The charger advice is spoken ONLY when charging is slow AND the temperature is rising. Quiet when the
        // temperature is normal or falling, when the charger has shown it is fast-capable, near full (the phone
        // tapers on purpose), or when the level or power is unknown. The fixed 40 and 45 degree warnings are separate.
        val slowNow = powerWatts != null && powerWatts < SLOW_WATTS
        val allowed = slowNow && maxWattsSeen < FAST_WATTS && levelPercent != null && levelPercent < NEAR_FULL_PERCENT &&
            slope != null && slope > 0f
        val longSlowAndWarming = slowSince != null && nowMs - slowSince!! >= SLOW_FOR_MS && tempC >= SUSPECT_MIN_C &&
            slowStartC != null && tempC - slowStartC!! >= SUSPECT_RISE_C
        val candidate: Advice? = when {
            !allowed -> null
            // A USB port is slow by design and may be carrying a file transfer, so it is not blamed on the charger.
            longSlowAndWarming && viaUsbPort == true -> Advice.USB_PORT_HEAT
            longSlowAndWarming -> Advice.CHARGER_SUSPECT
            tempC < EXISTING_WARNING_C && tempC >= PRE_DANGER_C && slope!! >= PRE_DANGER_SLOPE_C_PER_MIN -> Advice.PRE_DANGER
            tempC < PRE_DANGER_C && tempC >= WARM_C && slope!! >= EARLY_SLOPE_C_PER_MIN -> Advice.EARLY_RISE
            else -> null
        }
        if (candidate == null) return null
        val prev = lastSpoken[candidate]
        if (prev != null && nowMs - prev < COOLDOWN_MS) return null
        lastSpoken[candidate] = nowMs
        return candidate
    }

    fun text(advice: Advice, tempC: Float): String {
        val t = String.format(java.util.Locale.US, "%.1f", tempC)
        return when (advice) {
            Advice.EARLY_RISE -> "Your phone is warming up while charging, now $t degrees and still rising. Close background apps, take off the case, and avoid heavy use."
            Advice.PRE_DANGER -> "Phone temperature is $t degrees and rising toward the danger zone. Please unplug the charger now and let the phone cool down."
            Advice.USB_PORT_HEAT -> "Charging through a USB port is slow and the phone is warming, now $t degrees. A file transfer or a low power port can cause this, so it may not be the charger. If it keeps rising, unplug and let the phone cool."
            Advice.CHARGER_SUSPECT -> "Charging is slow and the phone is heating, now $t degrees. The charger or cable may be faulty. Try a different cable or charger."
        }
    }
}
