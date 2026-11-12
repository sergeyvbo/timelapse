package dev.timelapse.service

import dev.timelapse.data.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure schedule logic.
 *
 * Each day has one shooting segment: from the end of last night (night start + night hours)
 * until the next night starts. The first shot is [EDGE] after the segment starts, the following ones
 * every N minutes after that, and the last one is always [EDGE] before the night starts.
 * Example: night 21:00 for 9 h, N = 60 → 06:00:15, 07:00:15, ..., 20:00:15, 20:59:45.
 */
object Scheduler {
    val EDGE: Duration = Duration.ofSeconds(15)

    /** First shot time strictly after [after]. */
    fun nextSlot(after: Instant, settings: Settings, zone: ZoneId): Instant {
        val s = settings.sanitized()
        val interval = Duration.ofMinutes(s.intervalMinutes.toLong())
        val date = after.atZone(zone).toLocalDate()
        for (offset in -2L..2L) {
            val (first, last) = window(date.plusDays(offset), s, zone) ?: continue
            if (!last.isAfter(after)) continue
            if (first.isAfter(after)) return first
            val k = Duration.between(first, after).toMillis() / interval.toMillis() + 1
            val t = first.plus(interval.multipliedBy(k))
            return if (t.isBefore(last)) t else last
        }
        error("No slot found after $after")
    }

    /** True if [slot] is the last shot of its day (the one [EDGE] before the night). */
    fun isLastOfDay(slot: Instant, settings: Settings, zone: ZoneId): Boolean {
        val s = settings.sanitized()
        val date = slot.atZone(zone).toLocalDate()
        return (-1L..1L).any { window(date.plusDays(it), s, zone)?.second == slot }
    }

    /**
     * The day a photo taken at [at] belongs to: the date on which the latest shooting segment started
     * at or before [at]. A shot taken during the night ("Take photo now") counts towards the day that just ended.
     */
    fun dayOf(at: Instant, settings: Settings, zone: ZoneId): LocalDate {
        val s = settings.sanitized()
        val date = at.atZone(zone).toLocalDate()
        return (-2L..1L)
            .map { segment(date.plusDays(it), s, zone).first }
            .filter { !it.isAfter(at) }
            .max()
            .atZone(zone).toLocalDate()
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

    /** First and last shot of the segment after the night beginning on [nightDate]; null if it is too short. */
    private fun window(nightDate: LocalDate, s: Settings, zone: ZoneId): Pair<Instant, Instant>? {
        val (start, end) = segment(nightDate, s, zone)
        val first = start.plus(EDGE)
        val last = end.minus(EDGE)
        return if (first.isAfter(last)) null else first to last
    }

    /** The shooting segment that starts when the night beginning on [nightDate] ends. */
    private fun segment(nightDate: LocalDate, s: Settings, zone: ZoneId): Pair<Instant, Instant> {
        val nightStart = LocalTime.of(s.nightStartMinutes / 60, s.nightStartMinutes % 60)
        // Wall-clock arithmetic so the morning start stays e.g. 06:00 across DST changes.
        val start = nightDate.atTime(nightStart).plusHours(s.nightHours.toLong()).atZone(zone).toInstant()
        val end = nightDate.plusDays(1).atTime(nightStart).atZone(zone).toInstant()
        return start to end
    }
}
