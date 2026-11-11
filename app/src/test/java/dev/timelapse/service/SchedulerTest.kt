package dev.timelapse.service

import dev.timelapse.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SchedulerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val hourly = Settings(intervalMinutes = 60, nightStartMinutes = 21 * 60, nightHours = 9)

    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(zone).toInstant()
    private fun next(after: String, s: Settings = hourly) = Scheduler.nextSlot(at(after), s, zone)

    @Test
    fun `daytime slots fall on the grid`() {
        assertEquals(at("2026-11-11T07:00"), next("2026-11-11T06:00"))
        assertEquals(at("2026-11-11T07:00"), next("2026-11-11T06:30"))
        assertEquals(at("2026-11-11T08:00"), next("2026-11-11T07:00"))
    }

    @Test
    fun `last slot before night then resumes at night end`() {
        assertEquals(at("2026-11-11T20:00"), next("2026-11-11T19:00"))
        assertEquals(at("2026-11-12T06:00"), next("2026-11-11T20:00"))
        assertEquals(at("2026-11-12T06:00"), next("2026-11-11T23:30"))
        assertEquals(at("2026-11-12T06:00"), next("2026-11-12T02:00"))
    }

    @Test
    fun `night ending exactly now gives next grid slot`() {
        assertEquals(at("2026-11-12T07:00"), next("2026-11-12T06:00"))
    }

    @Test
    fun `interval that does not divide the day`() {
        val s = hourly.copy(intervalMinutes = 7 * 60)
        assertEquals(at("2026-11-11T13:00"), next("2026-11-11T06:00", s))
        assertEquals(at("2026-11-11T20:00"), next("2026-11-11T13:00", s))
        assertEquals(at("2026-11-12T06:00"), next("2026-11-11T20:00", s))
    }

    @Test
    fun `night window spanning nothing - shoot around the clock`() {
        val s = hourly.copy(nightHours = 0, intervalMinutes = 30)
        assertEquals(at("2026-11-12T03:30"), next("2026-11-12T03:10", s))
    }

    @Test
    fun `night starting after midnight`() {
        val s = hourly.copy(nightStartMinutes = 1 * 60, nightHours = 5) // 01:00-06:00
        assertEquals(at("2026-11-12T00:00"), next("2026-11-11T23:00", s))
        assertEquals(at("2026-11-12T06:00"), next("2026-11-12T00:00", s))
    }

    @Test
    fun `dst change keeps wall clock grid at night end`() {
        // Europe/Berlin falls back on 2026-10-25 03:00 -> 02:00
        assertEquals(at("2026-10-25T06:00"), next("2026-10-24T20:30"))
        assertEquals(at("2026-10-25T07:00"), next("2026-10-25T06:00"))
    }

    @Test
    fun `isNight`() {
        assertTrue(Scheduler.isNight(at("2026-11-11T22:00"), hourly, zone))
        assertTrue(Scheduler.isNight(at("2026-11-12T05:59"), hourly, zone))
        assertFalse(Scheduler.isNight(at("2026-11-12T06:00"), hourly, zone))
        assertFalse(Scheduler.isNight(at("2026-11-12T20:59"), hourly, zone))
    }
}
