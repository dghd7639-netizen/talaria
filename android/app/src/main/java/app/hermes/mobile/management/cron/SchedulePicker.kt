package app.hermes.mobile.management.cron

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class ScheduleType(val label: String) {
    ONCE("一次性"), DAILY("每天"), WEEKLY("每周"), INTERVAL("每隔一段时间")
}

enum class IntervalUnit(val label: String, val suffix: String) {
    MINUTES("分钟", "m"), HOURS("小时", "h")
}

// Monday-first display; standard cron numbers are Sunday=0, Monday=1, ... Saturday=6.
val cronWeekdays = listOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四",
    5 to "周五", 6 to "周六", 0 to "周日")

data class SchedulePickerState(
    val type: ScheduleType = ScheduleType.DAILY,
    val date: LocalDate = LocalDate.now(),
    val hour: Int = 9,
    val minute: Int = 0,
    val weekdays: Set<Int> = setOf(1),
    val interval: Int = 30,
    val unit: IntervalUnit = IntervalUnit.MINUTES,
) {
    fun withYear(year: Int) = copy(date = date.withYear(year))
    fun withMonth(month: Int) = copy(date = date.withMonth(month))

    fun isPast(clock: Clock): Boolean = type == ScheduleType.ONCE &&
        date.atTime(hour, minute).isBefore(LocalDateTime.now(clock))

    fun validation(creating: Boolean = false, clock: Clock = Clock.systemDefaultZone()): String? = when {
        hour !in 0..23 || minute !in 0..59 -> "请选择有效的小时和分钟。"
        type == ScheduleType.ONCE && date.year !in 1..9999 -> "年份须在 1 至 9999 之间。"
        type == ScheduleType.WEEKLY && (weekdays.isEmpty() || weekdays.any { it !in 0..6 }) -> "请至少选择一个星期。"
        type == ScheduleType.INTERVAL && interval !in 1..59 -> "间隔须在 1 至 59 之间。"
        creating && isPast(clock) -> "一次性执行时间不能早于当前时间，请重新选择。"
        else -> null
    }

    fun toSchedule(): String {
        require(validation() == null) { validation().orEmpty() }
        return when (type) {
            ScheduleType.ONCE -> date.atTime(hour, minute).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"))
            ScheduleType.DAILY -> "$minute $hour * * *"
            ScheduleType.WEEKLY -> "$minute $hour * * ${weekdays.sorted().joinToString(",")}"
            ScheduleType.INTERVAL -> "every $interval${unit.suffix}"
        }
    }
}

fun daysInMonth(year: Int, month: Int): Int = YearMonth.of(year, month).lengthOfMonth()

fun normalizeScheduleInput(input: String): String = input.trim()

fun scheduleInputError(input: String): String? = when {
    input.any { it.code > 127 } -> "执行时间只能使用半角英文字符、数字和符号；请删除中文句号、全角数字等字符。"
    normalizeScheduleInput(input).isBlank() -> "请输入执行时间。"
    else -> null
}

// Advanced ISO input can contain seconds or an explicit offset even though the wheels cannot.
fun isPastSchedule(input: String, clock: Clock): Boolean {
    val text = normalizeScheduleInput(input)
    val offsetTime = runCatching { OffsetDateTime.parse(text) }.getOrNull()
    if (offsetTime != null) return offsetTime.toInstant().isBefore(clock.instant())
    val localTime = runCatching { LocalDateTime.parse(text) }.getOrNull()
        ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
    return localTime?.isBefore(LocalDateTime.now(clock)) == true
}

/** Parses scheduleInput, including the DTO's minute-based interval representation.
 * Offsets and nonzero seconds cannot be represented by these local, minute-resolution wheels.
 * Leave those schedules untouched in the read-only/advanced editor instead of dropping data.
 */
fun parseSchedulePicker(input: String, date: LocalDate = LocalDate.now()): SchedulePickerState? {
    if (scheduleInputError(input) != null) return null
    val text = normalizeScheduleInput(input)
    val base = SchedulePickerState(date = date)
    Regex("every ([0-9]+)([mh])").matchEntire(text)?.let { match ->
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val hours = match.groupValues[2] == "h"
        return when {
            value in 1..59 -> base.copy(type = ScheduleType.INTERVAL, interval = value,
                unit = if (hours) IntervalUnit.HOURS else IntervalUnit.MINUTES)
            !hours && value % 60 == 0 && value / 60 in 1..59 ->
                base.copy(type = ScheduleType.INTERVAL, interval = value / 60, unit = IntervalUnit.HOURS)
            else -> null
        }
    }
    if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:00)?").matches(text)) {
        val time = runCatching { LocalDateTime.parse(text) }.getOrNull() ?: return null
        return base.copy(type = ScheduleType.ONCE, date = time.toLocalDate(), hour = time.hour,
            minute = time.minute).takeIf { it.validation() == null }
    }
    val fields = text.split(Regex("\\s+"))
    if (fields.size != 5 || fields[2] != "*" || fields[3] != "*") return null
    if (!Regex("[0-9]{1,2}").matches(fields[0]) || !Regex("[0-9]{1,2}").matches(fields[1])) return null
    val minute = fields[0].toInt()
    val hour = fields[1].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    if (fields[4] == "*") return base.copy(hour = hour, minute = minute)
    if (!Regex("[0-6](,[0-6])*").matches(fields[4])) return null
    return base.copy(type = ScheduleType.WEEKLY, hour = hour, minute = minute,
        weekdays = fields[4].split(',').map { it.toInt() }.toSet())
}
