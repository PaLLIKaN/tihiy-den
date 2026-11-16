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

    @Test fun usesDeadlineRatherThanStartTimeAndSupportsBothLeadTimes() {
        val task = DayEntry(date = date.minusDays(2), time = "09:30", dueDate = date, dueTime = LocalTime.of(20, 15))
        val oneHour = taskReminders(listOf(task), now, zone, 1).single()
        val twoHours = taskReminders(listOf(task), now, zone, 2).single()
        assertEquals(Instant.parse("2026-11-16T15:15:00Z"), oneHour.deadline)
        assertEquals(Instant.parse("2026-11-16T14:15:00Z"), oneHour.trigger)
        assertEquals(Instant.parse("2026-11-16T13:15:00Z"), twoHours.trigger)
        assertEquals(oneHour.deadlineKey, twoHours.deadlineKey)
    }

    @Test fun dateOnlyUsesEveningAndExpiredDeadlinesAreSkipped() {
        val task = DayEntry(id = "due", date = date, dueDate = date)
        val plans = taskReminders(listOf(task, task.copy(dueDate = null), task.copy(dueTime = LocalTime.NOON),
            task.copy(kind = EntryKind.EVENT, dueDate = null)), now, zone, 1)
        assertEquals(task, plans.single().task)
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), plans.single().trigger)
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
