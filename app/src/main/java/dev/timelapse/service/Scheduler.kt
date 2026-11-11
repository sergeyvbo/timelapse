package dev.timelapse.service

import dev.timelapse.data.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure schedule logic.
 *
 * Each day has one shooting segment: from the end of last night (night start + night hours)
 * until the next night starts. Shots fall on a grid anchored at the segment start:
 * segmentStart, segmentStart + N, segmentStart + 2N, ... while still before the night.
 * Example: night 21:00 for 9 h, N = 60 → 06:00, 07:00, ..., 20:00.
 */
object Scheduler {

    /** First shot time strictly after [after]. */
    fun nextSlot(after: Instant, settings: Settings, zone: ZoneId): Instant {
        val s = settings.sanitized()
        val interval = Duration.ofMinutes(s.intervalMinutes.toLong())
        val date = after.atZone(zone).toLocalDate()
        for (offset in -2L..2L) {
            val (segStart, segEnd) = segment(date.plusDays(offset), s, zone)
            if (!segEnd.isAfter(after) || !segStart.isBefore(segEnd)) continue
            if (segStart.isAfter(after)) return segStart
            val k = Duration.between(segStart, after).toMillis() / interval.toMillis() + 1
            val t = segStart.plus(interval.multipliedBy(k))
            if (t.isBefore(segEnd)) return t
        }
        error("No slot found after $after")
    }

    fun isNight(at: Instant, settings: Settings, zone: ZoneId): Boolean {
        val s = settings.sanitized()
        val date = at.atZone(zone).toLocalDate()
        for (offset in -1L..0L) {
            val (segStart, segEnd) = segment(date.plusDays(offset), s, zone)
            if (!at.isBefore(segStart) && at.isBefore(segEnd)) return false
        }
        return true
    }

    /** The shooting segment that starts when the night beginning on [nightDate] ends. */
    private fun segment(nightDate: java.time.LocalDate, s: Settings, zone: ZoneId): Pair<Instant, Instant> {
        val nightStart = LocalTime.of(s.nightStartMinutes / 60, s.nightStartMinutes % 60)
        // Wall-clock arithmetic so the morning start stays e.g. 06:00 across DST changes.
        val start = nightDate.atTime(nightStart).plusHours(s.nightHours.toLong()).atZone(zone).toInstant()
        val end = nightDate.plusDays(1).atTime(nightStart).atZone(zone).toInstant()
        return start to end
    }
}
