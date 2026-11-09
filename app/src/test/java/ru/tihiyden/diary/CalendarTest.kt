package ru.tihiyden.diary

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarTest {
    @Test fun everyMonthHasCompleteMondayFirstWeeks() {
        for (year in 2024..2032) for (month in 1..12) {
            val ym = YearMonth.of(year, month)
            val days = calendarDays(ym)
            assertEquals(0, days.size % 7)
            assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
            assertEquals(DayOfWeek.SUNDAY, days.last().dayOfWeek)
            assertEquals(ym.lengthOfMonth(), days.count { YearMonth.from(it) == ym })
            assertEquals(days.size, days.distinct().size)
            days.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
        }
    }

    @Test fun leapDayIsIncluded() {
        assertTrue(calendarDays(YearMonth.of(2028, 2)).contains(LocalDate.of(2028, 2, 29)))
    }

    @Test fun sixWeekMonthIsNotTruncated() {
        val days = calendarDays(YearMonth.of(2026, 3))
        assertEquals(42, days.size)
        assertTrue(days.contains(LocalDate.of(2026, 3, 31)))
    }

    @Test fun movingFrom31stClampsToLastDayOfFebruary() {
        assertEquals(LocalDate.of(2027, 2, 28), selectedDateInMonth(LocalDate.of(2027, 1, 31), YearMonth.of(2027, 2)))
        assertEquals(LocalDate.of(2028, 2, 29), selectedDateInMonth(LocalDate.of(2028, 1, 31), YearMonth.of(2028, 2)))
    }

    @Test fun agendaFiltersDateAndPutsCompletedTasksLast() {
        val date = LocalDate.of(2026, 10, 26)
        val entries = listOf(
            DayEntry(id = "done", date = date, time = "08:00", done = true),
            DayEntry(id = "untimed", date = date),
            DayEntry(id = "later", date = date, time = "14:00"),
            DayEntry(id = "other-day", date = date.plusDays(1)),
            DayEntry(id = "earlier", date = date, time = "09:00", kind = EntryKind.EVENT),
        )
        assertEquals(listOf("earlier", "later", "untimed", "done"), entriesForDay(entries, date).map { it.id })
    }

    @Test fun taskAppearsOncePerDayAcrossMonthBoundaryIncludingDeadline() {
        val start = LocalDate.of(2028, 2, 28)
        val due = LocalDate.of(2028, 3, 2)
        val task = DayEntry(date = start, dueDate = due, done = true)
        val event = DayEntry(date = due, kind = EntryKind.EVENT)
        val entries = listOf(task, event)
        for (day in listOf(start, start.plusDays(1), due.minusDays(1), due)) {
            assertEquals(listOf(task), entriesForDay(entries, day).filter { it.kind == EntryKind.TASK })
        }
        assertTrue(entriesForDay(entries, start.minusDays(1)).isEmpty())
        assertTrue(entriesForDay(entries, due.plusDays(1)).isEmpty())
        val days = calendarDays(YearMonth.from(due))
        val marks = entryMarksForGrid(entries, days)
        assertEquals(DayEntryMarks(1, 1), marks[days.indexOf(due)])
        assertEquals(4, marks.sumOf { it.tasks })
    }

    @Test fun taskPriorityAndStatusCombineWhileEventsStayVisible() {
        val day = LocalDate.of(2026, 11, 5)
        val high = DayEntry(id = "high", date = day, priority = TaskPriority.HIGH)
        val done = high.copy(id = "done", done = true)
        val normal = DayEntry(id = "normal", date = day, time = "08:00")
        val low = DayEntry(id = "low", date = day, time = "07:00", priority = TaskPriority.LOW)
        val event = DayEntry(id = "event", date = day, kind = EntryKind.EVENT, time = "09:00")
        val agenda = entriesForDay(listOf(done, normal, event, low, high), day)
        assertEquals(listOf(high, normal, event, low, done), agenda)
        assertEquals(listOf(high, event), agenda.filter(TaskFilter(TaskStatus.OPEN, TaskPriority.HIGH)::includes))
        assertEquals(listOf(event, done), agenda.filter(TaskFilter(TaskStatus.DONE, TaskPriority.HIGH)::includes))
    }
}
