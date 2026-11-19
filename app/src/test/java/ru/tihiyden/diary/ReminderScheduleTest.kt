package ru.tihiyden.diary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderScheduleTest {
    private val zone = ZoneId.of("Asia/Yekaterinburg")
    private val date = LocalDate.of(2026, 11, 16)
    private val now = Instant.parse("2026-11-16T10:00:00Z")

    @Test fun deadlineAndLegacyDueTimeDoNotChangeEitherStartReminder() {
        val task = DayEntry(date = date, time = "16:30", dueDate = date.plusDays(2), dueTime = LocalTime.of(20, 15))
        val plans = taskReminders(listOf(task), now, zone, 1)
        val withoutDeadline = taskReminders(listOf(task.copy(dueDate = null, dueTime = null)), now, zone, 1)
        assertEquals(2, plans.size)
        assertEquals(withoutDeadline.map { it.trigger }, plans.map { it.trigger })
        assertEquals(Instant.parse("2026-11-16T10:30:00Z"), plans[0].trigger)
        assertEquals(Instant.parse("2026-11-16T11:30:00Z"), plans[1].trigger)
        assertEquals(listOf(ReminderPhase.ADVANCE, ReminderPhase.START), plans.map { it.phase })
    }

    @Test fun scheduledStartWithoutDeadlineTriggersOneHourBeforeIt() {
        val task = DayEntry(date = date, time = "16:30")
        val event = task.copy(id = "event", kind = EntryKind.EVENT)
        val reminder = taskReminders(listOf(task, event), now, zone, 1).first()
        assertEquals(task, reminder.task)
        assertEquals(Instant.parse("2026-11-16T10:30:00Z"), reminder.trigger)
        assertEquals(Instant.parse("2026-11-16T11:30:00Z"), reminder.target)
        assertEquals(Instant.parse("2026-11-16T09:30:00Z"), taskReminders(listOf(task), now, zone, 2).first().trigger)
    }

    @Test fun dateOnlyAndExpiredStartsDoNotCreateNewReminders() {
        val task = DayEntry(id = "due", date = date, dueDate = date)
        val plans = taskReminders(listOf(task, task.copy(dueDate = null), task.copy(dueTime = LocalTime.NOON),
            task.copy(kind = EntryKind.EVENT, dueDate = null)), now, zone, 1)
        assertEquals(emptyList<TaskReminder>(), plans)
    }

    @Test fun lateDeliveryKeepsRecentlyMissedScheduledTasks() {
        val task = DayEntry(id = "scheduled", date = date, time = "14:30")
        val neverArmed = task.copy(id = "older", time = "14:15")
        val armedAt = Instant.parse("2026-11-16T09:30:00Z")
        assertEquals(listOf(task), taskReminders(listOf(task, neverArmed), now, zone, 1, armedAt).map { it.task })
        assertEquals(ReminderPhase.START, taskReminders(listOf(task), now, zone, 1, armedAt).single().phase)
        assertEquals(emptyList<TaskReminder>(), taskReminders(listOf(task), now, zone, 1))
        assertEquals(emptyList<TaskReminder>(), taskReminders(listOf(task), now.plusSeconds(1800), zone, 1, armedAt))
    }

    @Test fun sundayCanCatchUpAndTheSameWeekendIsNotRepeated() {
        val saturday = date.plusDays(5)
        assertEquals(saturday, weekendReminderDate(date, null))
        assertEquals(saturday, weekendReminderDate(saturday.plusDays(1), null))
        assertEquals(saturday.plusWeeks(1), weekendReminderDate(saturday, saturday.toString()))
        assertEquals(saturday.plusWeeks(1), weekendReminderDate(saturday.plusDays(1), saturday.toString()))
    }

    @Test fun weekendPoolIncludesEveryMediaKindAndExcludesFinishedItems() {
        val choices = listOf(
            MediaEntry(kind = MediaKind.BOOK, status = MediaStatus.IN_PROGRESS),
            MediaEntry(kind = MediaKind.MOVIE),
            MediaEntry(kind = MediaKind.SERIES),
        )
        assertEquals(choices, weekendSuggestions(choices + MediaEntry(status = MediaStatus.DONE)))
    }
}
