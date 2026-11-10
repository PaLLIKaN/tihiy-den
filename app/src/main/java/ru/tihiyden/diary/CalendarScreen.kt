package ru.tihiyden.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val Russian: Locale = Locale.forLanguageTag("ru-RU")
fun String.capitalized(): String = replaceFirstChar { it.titlecase(Russian) }

private data class HolidayColor(val ink: Color, val tint: Color, val selectedMark: Color)

private val TaskInk = Color(0xFF216449)
private val TaskTint = Color(0xFFE5F4EB)
private val EventInk = Color(0xFF7043A5)
private val EventTint = Color(0xFFF2E9FB)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(entries: List<DayEntry>, selected: LocalDate, onSelect: (LocalDate) -> Unit, onEdit: (DayEntry) -> Unit, onToggle: (DayEntry) -> Unit, holidayState: HolidayUiState = HolidayUiState(), holidaySettings: HolidaySettings = HolidaySettings(), onDeleteRequest: ((DayEntry) -> Unit)? = null) {
    val month = YearMonth.from(selected)
    val today = LocalDate.now()
    val days = remember(month) { calendarDays(month) }
    val grid = remember(entries, days) { days.zip(entryMarksForGrid(entries, days)).chunked(7) }
    val dayEntries = remember(entries, selected) { entriesForDay(entries, selected) }
    var statusName by rememberSaveable { mutableStateOf(TaskStatus.ALL.name) }
    var priorityName by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var previewDay by remember(selected) { mutableStateOf<LocalDate?>(null) }
    val filter = TaskFilter(TaskStatus.valueOf(statusName), priorityName?.let(TaskPriority::valueOf))
    val visibleEntries = remember(dayEntries, filter) { dayEntries.filter(filter::includes) }
    val tasks = dayEntries.count { it.kind == EntryKind.TASK }
    val activeFilters = filter.labels
    val resetFilters = { statusName = TaskStatus.ALL.name; priorityName = null }
    val byHoliday = remember(holidayState.holidays, holidaySettings) { holidayState.holidays.filter { holidaySettings.shows(it.kind) }.groupBy { it.date } }
    val dayHolidays = byHoliday[selected].orEmpty()
    val holidayColors = remember(holidaySettings.colors) {
        HolidayKind.entries.associateWith { kind ->
            val color = holidaySettings.color(kind)
            if (color == kind.defaultColor) HolidayColor(Color(color), Color(kind.defaultTint), Color(kind.defaultTint))
            else HolidayColor(diaryColorScheme(color).primary, lerp(Color(color), Color.White, .86f), lerp(Color(color), Color.White, .42f))
        }
    }
    val production = holidayState.calendar?.takeIf { it.year == selected.year }
    val selectedDayType = if (holidaySettings.enabled && holidaySettings.dayTypes && production != null) {
        production.days[selected]?.label ?: if (selected.dayOfWeek.value >= 6) "Выходной день" else "Рабочий день"
    } else null
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Russian)).capitalized(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onSelect(selectedDateInMonth(selected, month.minusMonths(1))) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Предыдущий месяц") }
                        IconButton(onClick = { onSelect(selectedDateInMonth(selected, month.plusMonths(1))) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Следующий месяц") }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС").forEach {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    }
                    grid.forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { (day, marks) ->
                                val selectedDay = day == selected
                                val inMonth = YearMonth.from(day) == month
                                val dayObservances = byHoliday[day].orEmpty()
                                val categories = HolidayKind.entries.filter { kind -> dayObservances.any { it.kind == kind } }
                                Box(Modifier.weight(1f).height(54.dp), contentAlignment = Alignment.Center) {
                                    Column(
                                        Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                                            .background(if (selectedDay) Sage else Color.Transparent)
                                            .then(if (day == today && !selectedDay) Modifier.border(1.dp, Sage, RoundedCornerShape(16.dp)) else Modifier)
                                            .combinedClickable(
                                                onClick = { onSelect(day) },
                                                onLongClickLabel = "Показать задачи дня",
                                                onLongClick = { previewDay = day },
                                            )
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Russian)) +
                                                    (if (day == today) ", сегодня" else "") +
                                                    dayObservances.joinToString(separator = "; ", prefix = if (dayObservances.isEmpty()) "" else ". ") { "${it.title} (${it.kind.label.lowercase(Russian)})" }
                                                this.selected = selectedDay
                                                stateDescription = "Записей: ${marks.tasks + marks.events}"
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selectedDay || day == today || categories.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                            color = when {
                                                selectedDay -> MaterialTheme.colorScheme.onPrimary
                                                categories.isNotEmpty() -> holidayColors.getValue(categories.first()).ink
                                                !inMonth -> Muted.copy(alpha = .5f)
                                                else -> Ink
                                            })
                                        Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                                            if (marks.tasks > 0) Box(Modifier.size(5.dp).background(if (selectedDay) Color(0xFFA0E2BB) else TaskInk, CircleShape))
                                            if (marks.events > 0) Box(Modifier.size(5.dp).background(if (selectedDay) Color(0xFFDEBFFF) else EventInk, CircleShape))
                                            categories.forEach { kind -> HolidayMark(holidayColors.getValue(kind), selectedDay) }
                                        }
                                    }
                                    if (previewDay == day) {
                                        val dayTasks = remember(entries, day) {
                                            entriesForDay(entries, day).filter { it.kind == EntryKind.TASK }
                                        }
                                        DayTasksPopup(day, dayTasks, TaskInk) { previewDay = null }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DotLabel(TaskInk, "Задачи")
                        Spacer(Modifier.width(12.dp))
                        DotLabel(EventInk, "События")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onSelect(today) }) { Text("Сегодня") }
                    }
                    if (holidaySettings.enabled) {
                        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HolidayKind.entries.filter(holidaySettings::shows).forEach { kind ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    val colors = holidayColors.getValue(kind)
                                    HolidayMark(colors)
                                    Text(kind.pluralLabel, style = MaterialTheme.typography.labelSmall, color = colors.ink)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected.format(DateTimeFormatter.ofPattern("d MMMM", Russian)), style = MaterialTheme.typography.headlineSmall)
                    Text(selected.format(DateTimeFormatter.ofPattern("EEEE", Russian)).capitalized(), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                val completed = dayEntries.count { it.kind == EntryKind.TASK && it.done }
                if (tasks > 0) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text("$completed из $tasks задач", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (selectedDayType != null) item { Text(selectedDayType, style = MaterialTheme.typography.bodySmall, color = Muted) }
        if (holidaySettings.enabled && holidaySettings.dayTypes && holidayState.loaded && production == null) item {
            Text("Переносы выходных на ${selected.year} год пока не загружены.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        items(dayHolidays, key = { it.id }) { holiday ->
            val colors = holidayColors.getValue(holiday.kind)
            Surface(color = colors.tint, contentColor = Ink, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (holiday.kind == HolidayKind.PROFESSIONAL) Icons.Outlined.WorkOutline else Icons.Outlined.EventAvailable, null, tint = colors.ink, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(holiday.title, style = MaterialTheme.typography.titleSmall)
                        Text(holiday.kind.label, style = MaterialTheme.typography.bodySmall, color = colors.ink)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showFilters = true }) {
                    Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (activeFilters.isEmpty()) "Фильтры задач" else "Фильтры задач · ${activeFilters.size}")
                }
                if (activeFilters.isNotEmpty()) Text(activeFilters.joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        if (dayEntries.isEmpty()) item {
            EmptyState(Icons.Outlined.WbSunny, if (dayHolidays.isEmpty()) "День пока свободен" else "Личных дел пока нет", "Добавьте задачу или событие.\nОставьте время и для себя.")
        }
        if (tasks > 0 && visibleEntries.none { it.kind == EntryKind.TASK }) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Нет задач по этим фильтрам", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Muted)
                TextButton(onClick = resetFilters) { Text("Сбросить") }
            }
        }
        items(visibleEntries, key = { it.id }) { entry ->
            val isTask = entry.kind == EntryKind.TASK
            val entryColor = if (isTask) TaskInk else EventInk
            Surface(
                modifier = Modifier.recordActions({ onEdit(entry) }, onDeleteRequest?.let { request -> { request(entry) } }),
                shape = RecordCardShape, color = if (isTask) TaskTint else EventTint, contentColor = Ink,
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isTask) Checkbox(checked = entry.done, onCheckedChange = { onToggle(entry) }, colors = CheckboxDefaults.colors(checkedColor = entryColor, uncheckedColor = entryColor), modifier = Modifier.semantics { contentDescription = "Выполнено: ${entry.title}" })
                    else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Schedule, null, tint = entryColor) }
                    Column(Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 8.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, color = if (entry.done) Muted else Ink, textDecoration = if (entry.done) TextDecoration.LineThrough else null)
                        if (entry.details.isNotBlank()) Text(entry.details, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(listOf(entry.time, entry.kind.label).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = entryColor, modifier = Modifier.padding(top = 6.dp))
                        if (isTask) {
                            val priorityColor = if (entry.priority == TaskPriority.HIGH && !entry.done) MaterialTheme.colorScheme.error else Muted
                            Text("Приоритет: ${entry.priority.label.lowercase(Russian)}", style = MaterialTheme.typography.labelSmall, color = priorityColor)
                            entry.dueDate?.let { due ->
                                val pattern = if (due.year == selected.year) "d MMMM" else "d MMMM yyyy"
                                Text("Срок: ${due.format(DateTimeFormatter.ofPattern(pattern, Russian))}", style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    }
                    Icon(Icons.Outlined.MoreHoriz, "Открыть запись", tint = Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    if (showFilters) TaskFiltersDialog(filter, onDismiss = { showFilters = false }, onApply = {
        statusName = it.status.name
        priorityName = it.priority?.name
        showFilters = false
    })
}

@Composable
private fun HolidayMark(colors: HolidayColor, selected: Boolean = false) {
    Box(Modifier.size(width = 7.dp, height = 5.dp).background(if (selected) colors.selectedMark else colors.ink, RoundedCornerShape(1.dp)))
}

@Composable
fun DotLabel(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
