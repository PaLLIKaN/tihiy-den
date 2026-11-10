package ru.tihiyden.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun DayTasksPopup(day: LocalDate, tasks: List<DayEntry>, taskColor: Color, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap) { AboveDayPosition(gap) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMMM", Russian) }
    val fullDateFormat = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", Russian) }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            modifier = Modifier.width(272.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 8.dp,
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 236.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Задачи · " + day.format(dayFormat),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (tasks.isEmpty()) item {
                    Text("На этот день нет задач", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
                items(tasks, key = { it.id }) { task ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val start = if (task.date == day) task.time else {
                            val date = task.date.format(if (task.date.year == day.year) dayFormat else fullDateFormat)
                            if (task.time.isBlank()) date else "$date, ${task.time}"
                        }
                        Text(
                            if (start.isBlank()) "Без времени" else "Начало · $start",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (start.isBlank()) Muted else taskColor,
                        )
                        Text(task.title, style = MaterialTheme.typography.bodyMedium)
                        if (task.details.isNotBlank()) {
                            Text(task.details, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
            }
        }
    }
}

private class AboveDayPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centered = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val top = if (above >= 0) above else below
        return IntOffset(
            centered.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
