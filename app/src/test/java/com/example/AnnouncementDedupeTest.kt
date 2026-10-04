package com.example

import com.example.service.AnnouncementEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementDedupeTest {
    private val w = AnnouncementEngine.DEDUPE_WINDOW_MS

    @Test fun firstTimeIsNotADuplicate() {
        assertFalse(AnnouncementEngine.isRecentDuplicate(emptyMap(), "Charger connected.", 1_000L, w))
    }
    @Test fun sameTextInsideWindowIsRepeat() {
        val seen = mapOf("Charger connected." to 1_000L)
        assertTrue(AnnouncementEngine.isRecentDuplicate(seen, "Charger connected.", 1_000L + w - 1, w))
    }
    @Test fun sameTextAfterWindowIsAllowed() {
        val seen = mapOf("Charger connected." to 1_000L)
        assertFalse(AnnouncementEngine.isRecentDuplicate(seen, "Charger connected.", 1_000L + w, w))
    }
    @Test fun differentTextIsNotARepeat() {
        val seen = mapOf("Charger connected." to 1_000L)
        assertFalse(AnnouncementEngine.isRecentDuplicate(seen, "Charging started.", 1_500L, w))
    }
    @Test fun clockGoingBackwardsDoesNotSuppressForever() {
        val seen = mapOf("Charger connected." to 50_000L)
        assertFalse(AnnouncementEngine.isRecentDuplicate(seen, "Charger connected.", 10_000L, w))
    }

    @Test fun chargingSpeedWaitsSixtySeconds() {
        val cd = AnnouncementEngine.CATEGORY_COOLDOWN_MS.getValue("CHARGING_SPEED")
        assertTrue(cd >= 60_000L)
        assertTrue(AnnouncementEngine.isInCooldown(10_000L, 10_000L + 59_999L, cd))
        assertFalse(AnnouncementEngine.isInCooldown(10_000L, 10_000L + cd, cd))
        assertFalse(AnnouncementEngine.isInCooldown(null, 5L, cd))
    }
}
