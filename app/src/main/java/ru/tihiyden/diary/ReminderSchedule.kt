package ru.tihiyden.diary

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class ReminderSettings(
    val tasks: Boolean = false,
    val leadHours: Int = 1,
    val weekend: Boolean = false,
)

internal data class TaskReminder(
    val task: DayEntry,
    val target: Instant,
    val trigger: Instant,
    val targetKey: String,
    val phase: ReminderPhase,
)

internal enum class ReminderPhase { ADVANCE, START }

internal val DayEntry.reminderTarget: LocalDateTime?
    get() {
        if (kind != EntryKind.TASK) return null
        return time.takeIf(String::isNotBlank)
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.let(date::atTime)
    }

internal fun taskReminders(
    entries: List<DayEntry>,
    now: Instant,
    zone: ZoneId,
    leadHours: Int,
    armedAt: Instant? = null,
): List<TaskReminder> =
    entries.flatMap { task ->
        val localTarget = task.reminderTarget ?: return@flatMap emptyList()
        val target = localTarget.atZone(zone).toInstant()
        val trigger = target.minus(Duration.ofHours(leadHours.toLong()))
        val key = localTarget.toString()
        if (target > now) listOf(
            TaskReminder(task, target, trigger, key, ReminderPhase.ADVANCE),
            TaskReminder(task, target, target, key, ReminderPhase.START),
        ) else if (armedAt != null && target >= armedAt && now < target.plus(Duration.ofHours(1))) {
            listOf(TaskReminder(task, target, target, key, ReminderPhase.START))
        } else emptyList()
    }

internal fun weekendReminderDate(today: LocalDate, lastDelivered: String?): LocalDate {
    val saturday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1)
        else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    return if (saturday.toString() == lastDelivered) saturday.plusWeeks(1) else saturday
}

internal fun weekendSuggestions(media: List<MediaEntry>): List<MediaEntry> =
    media.filter { it.status != MediaStatus.DONE }
