package ru.dredd20.opencode.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPlanTest {
    @Test
    fun freshByDefaultLastOnRequest() {
        assertTrue(LocationPlan.of(null, null, 15).fresh)
        assertTrue(LocationPlan.of("once", null, 15).fresh)
        assertTrue(LocationPlan.of("updates", null, 15).fresh)
        assertFalse(LocationPlan.of("last", null, 15).fresh)
        assertEquals(60, LocationPlan.of(null, null, 999).timeoutSec)
        assertEquals(1, LocationPlan.of(null, null, 0).timeoutSec)
    }

    @Test
    fun providerOrder() {
        assertEquals(listOf("fused", "gps", "network"), LocationPlan.order(listOf("passive", "network", "gps", "fused"), null))
        assertEquals(listOf("gps"), LocationPlan.order(listOf("passive", "network", "gps"), "gps"))
        assertEquals(listOf("passive"), LocationPlan.order(listOf("passive", "gps"), "passive"))
        assertEquals(emptyList<String>(), LocationPlan.order(listOf("network"), "gps"))
    }
}
