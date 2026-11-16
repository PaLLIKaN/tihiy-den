package ru.tihiyden.diary

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class ReminderSettings(
    val tasks: Boolean = false,
    val leadHours: Int = 1,
    val weekend: Boolean = false,
)

internal data class TaskReminder(
    val task: DayEntry,
    val deadline: Instant,
    val trigger: Instant,
    val deadlineKey: String,
)

internal fun taskReminders(entries: List<DayEntry>, now: Instant, zone: ZoneId, leadHours: Int): List<TaskReminder> =
    entries.mapNotNull { task ->
        if (task.kind != EntryKind.TASK) return@mapNotNull null
        val localDeadline = task.deadline ?: return@mapNotNull null
        val deadline = localDeadline.atZone(zone).toInstant()
        if (deadline <= now) return@mapNotNull null
        TaskReminder(task, deadline, deadline.minus(Duration.ofHours(leadHours.toLong())), localDeadline.toString())
    }

internal fun weekendReminderDate(today: LocalDate, lastDelivered: String?): LocalDate {
    val saturday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1)
        else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    return if (saturday.toString() == lastDelivered) saturday.plusWeeks(1) else saturday
}

internal fun weekendSuggestions(media: List<MediaEntry>): List<MediaEntry> =
    media.filter { it.status != MediaStatus.DONE }
