package ru.tihiyden.diary

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class EntryKind(val label: String) { TASK("Задача"), EVENT("Событие") }
enum class TaskPriority(val label: String) { LOW("Низкий"), NORMAL("Обычный"), HIGH("Высокий") }
enum class TaskStatus(val label: String) { ALL("Все"), OPEN("В работе"), DONE("Выполнены") }

data class TaskFilter(val status: TaskStatus = TaskStatus.ALL, val priority: TaskPriority? = null) {
    val labels: List<String> get() = listOfNotNull(status.takeUnless { it == TaskStatus.ALL }?.label, priority?.label)

    fun includes(entry: DayEntry): Boolean = entry.kind == EntryKind.EVENT ||
        (priority == null || entry.priority == priority) && when (status) {
            TaskStatus.ALL -> true
            TaskStatus.OPEN -> !entry.done
            TaskStatus.DONE -> entry.done
        }
}

enum class MediaKind(val label: String) { BOOK("Книги"), MOVIE("Фильмы"), SERIES("Сериалы") }
enum class MediaStatus {
    PLANNED, IN_PROGRESS, DONE;
    fun label(kind: MediaKind): String = when (this) {
        PLANNED -> "В планах"
        IN_PROGRESS -> if (kind == MediaKind.BOOK) "Читаю" else "Смотрю"
        DONE -> if (kind == MediaKind.BOOK) "Прочитано" else "Просмотрено"
    }
}

data class DayEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val details: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: String = "",
    val kind: EntryKind = EntryKind.TASK,
    val done: Boolean = false,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val dueDate: LocalDate? = null,
) {
    val lastDay: LocalDate get() = if (kind == EntryKind.TASK) dueDate ?: date else date
    fun occursOn(day: LocalDate): Boolean = day >= date && day <= lastDay

    fun validateSchedule() {
        require(dueDate == null || dueDate >= date)
        require(kind == EntryKind.TASK || (priority == TaskPriority.NORMAL && dueDate == null))
    }
}

data class Thought(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class MediaEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val creator: String = "",
    val kind: MediaKind = MediaKind.BOOK,
    val status: MediaStatus = MediaStatus.PLANNED,
    val thoughts: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val cover: String = "",
)

data class DiaryData(
    val entries: List<DayEntry> = emptyList(),
    val thoughts: List<Thought> = emptyList(),
    val media: List<MediaEntry> = emptyList(),
)

/** Monday-first rows, including adjacent-month days so every row has seven cells. */
fun calendarDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val start = first.minusDays(offset.toLong())
    val count = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return List(count) { start.plusDays(it.toLong()) }
}

fun selectedDateInMonth(date: LocalDate, month: YearMonth): LocalDate =
    month.atDay(date.dayOfMonth.coerceAtMost(month.lengthOfMonth()))

fun entriesForDay(entries: List<DayEntry>, date: LocalDate): List<DayEntry> =
    entries.filter { it.occursOn(date) }
        .sortedWith(compareBy<DayEntry> { it.done }.thenByDescending { it.priority }
            .thenBy { it.time.ifBlank { "99:99" } }.thenBy { it.title })

data class DayEntryMarks(var tasks: Int = 0, var events: Int = 0)

/** Count only visible days, even if a task spans several months or years. */
fun entryMarksForGrid(entries: List<DayEntry>, days: List<LocalDate>): List<DayEntryMarks> {
    val marks = List(days.size) { DayEntryMarks() }
    if (days.isEmpty()) return marks
    val first = days.first().toEpochDay()
    val last = days.last().toEpochDay()
    for (entry in entries) {
        val start = maxOf(first, entry.date.toEpochDay())
        val end = minOf(last, entry.lastDay.toEpochDay())
        for (day in start..end) {
            val mark = marks[(day - first).toInt()]
            if (entry.kind == EntryKind.TASK) mark.tasks++ else mark.events++
        }
    }
    return marks
}
