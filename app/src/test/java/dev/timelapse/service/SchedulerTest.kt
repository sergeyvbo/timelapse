package dev.timelapse.service

import dev.timelapse.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SchedulerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val hourly = Settings(intervalMinutes = 60, nightStartMinutes = 21 * 60, nightHours = 9)

    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(zone).toInstant()
    private fun next(after: String, s: Settings = hourly) = Scheduler.nextSlot(at(after), s, zone)

    @Test
    fun `first shot 15 s after the day starts`() {
        assertEquals(at("2026-11-12T06:00:15"), next("2026-11-11T23:30"))
        assertEquals(at("2026-11-12T06:00:15"), next("2026-11-12T06:00"))
    }

    @Test
    fun `daytime slots fall on the grid anchored at the first shot`() {
        assertEquals(at("2026-11-11T07:00:15"), next("2026-11-11T06:00:15"))
        assertEquals(at("2026-11-11T07:00:15"), next("2026-11-11T06:30"))
        assertEquals(at("2026-11-11T08:00:15"), next("2026-11-11T07:00:15"))
    }

    @Test
    fun `last shot 15 s before the night, then resumes in the morning`() {
        assertEquals(at("2026-11-11T20:00:15"), next("2026-11-11T19:00:15"))
        assertEquals(at("2026-11-11T20:59:45"), next("2026-11-11T20:00:15"))
        assertEquals(at("2026-11-12T06:00:15"), next("2026-11-11T20:59:45"))
        assertEquals(at("2026-11-12T06:00:15"), next("2026-11-12T02:00"))
        assertTrue(Scheduler.isLastOfDay(at("2026-11-11T20:59:45"), hourly, zone))
        assertFalse(Scheduler.isLastOfDay(at("2026-11-11T20:00:15"), hourly, zone))
    }

    @Test
    fun `interval that does not divide the day`() {
        val s = hourly.copy(intervalMinutes = 7 * 60)
        assertEquals(at("2026-11-11T13:00:15"), next("2026-11-11T06:00:15", s))
        assertEquals(at("2026-11-11T20:00:15"), next("2026-11-11T13:00:15", s))
        assertEquals(at("2026-11-11T20:59:45"), next("2026-11-11T20:00:15", s))
        assertEquals(at("2026-11-12T06:00:15"), next("2026-11-11T20:59:45", s))
    }

    @Test
    fun `no night - shoot around the clock`() {
        val s = hourly.copy(nightHours = 0, intervalMinutes = 30)
        assertEquals(at("2026-11-12T03:30:15"), next("2026-11-12T03:10", s))
        assertEquals(at("2026-11-12T20:59:45"), next("2026-11-12T20:40", s))
        assertEquals(at("2026-11-12T21:00:15"), next("2026-11-12T20:59:45", s))
    }

    @Test
    fun `night starting after midnight`() {
        val s = hourly.copy(nightStartMinutes = 1 * 60, nightHours = 5) // 01:00-06:00
        assertEquals(at("2026-11-12T00:00:15"), next("2026-11-11T23:00:15", s))
        assertEquals(at("2026-11-12T00:59:45"), next("2026-11-12T00:00:15", s))
        assertEquals(at("2026-11-12T06:00:15"), next("2026-11-12T00:59:45", s))
        assertEquals(LocalDate.parse("2026-11-11"), Scheduler.dayOf(at("2026-11-12T00:30"), s, zone))
    }

    @Test
    fun `dst change keeps wall clock grid at night end`() {
        // Europe/Berlin falls back on 2026-10-25 03:00 -> 02:00
        assertEquals(at("2026-10-25T06:00:15"), next("2026-10-24T20:59:45"))
        assertEquals(at("2026-10-25T07:00:15"), next("2026-10-25T06:00:15"))
    }

    @Test
    fun `day of a photo`() {
        assertEquals(LocalDate.parse("2026-11-11"), Scheduler.dayOf(at("2026-11-11T06:00:15"), hourly, zone))
        assertEquals(LocalDate.parse("2026-11-11"), Scheduler.dayOf(at("2026-11-11T23:00"), hourly, zone))
        assertEquals(LocalDate.parse("2026-11-11"), Scheduler.dayOf(at("2026-11-12T05:59"), hourly, zone))
        assertEquals(LocalDate.parse("2026-11-12"), Scheduler.dayOf(at("2026-11-12T06:00"), hourly, zone))
    }

    @Test
    fun `isNight`() {
        assertTrue(Scheduler.isNight(at("2026-11-11T22:00"), hourly, zone))
        assertTrue(Scheduler.isNight(at("2026-11-12T05:59"), hourly, zone))
        assertFalse(Scheduler.isNight(at("2026-11-12T06:00"), hourly, zone))
        assertFalse(Scheduler.isNight(at("2026-11-12T20:59"), hourly, zone))
    }
}
