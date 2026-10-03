package app.hermes.mobile.management.cron

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SchedulePickerTest {
    private val date = LocalDate.of(2026, 12, 1)
    private val clock = Clock.fixed(Instant.parse("2026-12-01T01:00:00Z"), ZoneId.of("Asia/Taipei"))
    private val base = SchedulePickerState(date = date)

    @Test fun outputsAllFourTypesAndCronWeekdays() {
        assertEquals("0 9 * * *", base.toSchedule())
        assertEquals("2026-12-01T09:00", base.copy(type = ScheduleType.ONCE).toSchedule())
        assertEquals("every 30m", base.copy(type = ScheduleType.INTERVAL).toSchedule())
        assertEquals("every 2h", base.copy(type = ScheduleType.INTERVAL, interval = 2, unit = IntervalUnit.HOURS).toSchedule())
        // UI uses Monday first, but cron uses Sunday=0, Monday=1, ... Saturday=6.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 0), cronWeekdays.map { it.first })
        cronWeekdays.forEach { (number, _) ->
            assertEquals("5 23 * * $number", base.copy(type = ScheduleType.WEEKLY,
                hour = 23, minute = 5, weekdays = setOf(number)).toSchedule())
        }
        assertEquals("0 9 * * 0,1,6", base.copy(type = ScheduleType.WEEKLY, weekdays = setOf(6, 1, 0)).toSchedule())
        assertNotNull(base.copy(type = ScheduleType.WEEKLY, weekdays = emptySet()).validation())
    }

    @Test fun clampsDateToValidMonthIncludingLeapYears() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(2026, 2))
        assertEquals(28, daysInMonth(2100, 2))
        assertEquals(29, daysInMonth(2000, 2))
        assertEquals(30, daysInMonth(2026, 4))
        assertEquals(LocalDate.of(2024, 2, 29), base.copy(date = LocalDate.of(2024, 1, 31)).withMonth(2).date)
        assertEquals(LocalDate.of(2025, 2, 28), base.copy(date = LocalDate.of(2024, 2, 29)).withYear(2025).date)
    }

    @Test fun roundTripsAndNormalizesRepresentableIntervals() {
        listOf("2026-12-01T09:00", "0 9 * * *", "59 23 * * 0,1,6", "every 30m", "every 2h").forEach {
            assertEquals(it, parseSchedulePicker(it, date)?.toSchedule())
        }
        assertEquals("every 2h", parseSchedulePicker("every 120m", date)?.toSchedule())
        assertEquals("2026-12-01T09:00", parseSchedulePicker("2026-12-01T09:00:00", date)?.toSchedule())
        assertEquals("0 9 * * *", parseSchedulePicker("  0 9 * * *  ", date)?.toSchedule())
    }

    @Test fun refusesSchedulesThatWouldLoseMeaning() {
        listOf("*/5 * * * *", "0 9 * * 1-5", "0 9 1 * *", "0 9 * 2 *", "0 9,10 * * *",
            "0 9 * * 7", "0 9 * * 1,", "0 9 * * * *", "60 9 * * *", "0 24 * * *",
            "every 0m", "every 60h", "every 61m", "every 1.5m", "nonsense",
            "2026-02-29T09:00", "2026-12-01T09:00:01", "2026-12-01T09:00:00+08:00",
            "2026-12-01T09:00Z", "2026-12-01T09:00。").forEach {
            assertNull(it, parseSchedulePicker(it, date))
        }
    }

    @Test fun detectsPastTimesUsingInjectedClock() {
        val once = base.copy(type = ScheduleType.ONCE)
        assertTrue(once.copy(hour = 8, minute = 59).isPast(clock))
        assertFalse(once.isPast(clock))
        assertFalse(once.copy(minute = 1).isPast(clock))
        assertFalse(base.copy(date = date.minusYears(1)).isPast(clock))
        assertNotNull(once.copy(hour = 8).validation(creating = true, clock = clock))
        assertNull(once.copy(hour = 8).validation(creating = false, clock = clock))
        assertTrue(isPastSchedule("2026-12-01T08:59:59+08:00", clock))
        assertTrue(isPastSchedule("2026-12-01T00:59:59Z", clock))
        assertTrue(isPastSchedule("2026-12-01", clock))
        assertFalse(isPastSchedule("2026-12-01T09:00:01", clock))
        assertFalse(isPastSchedule("2026-12-01T02:00:00Z", clock))
        assertFalse(isPastSchedule("every 30m", clock))
    }

    @Test fun rejectsFullWidthCharactersBeforeSendingAndTrimsWhitespace() {
        assertEquals("every 30m", normalizeScheduleInput(" \nevery 30m\t "))
        assertNull(scheduleInputError(" every 30m "))
        listOf("2026-12-01T09:00。", "every ３０m", "0 9 * * １", "每隔30分钟").forEach {
            assertNotNull(it, scheduleInputError(it))
        }
        assertNotNull(scheduleInputError("  "))
    }
}
