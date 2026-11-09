package ru.tihiyden.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskFiltersDialog(filter: TaskFilter, onDismiss: () -> Unit, onApply: (TaskFilter) -> Unit) {
    var status by rememberSaveable { mutableStateOf(filter.status.name) }
    var priority by rememberSaveable { mutableStateOf(filter.priority?.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фильтры задач") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Статус", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskStatus.entries.forEach { option ->
                        FilterChip(selected = option.name == status, onClick = { status = option.name }, label = { Text(option.label) })
                    }
                }
                Text("Приоритет", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = priority == null, onClick = { priority = null }, label = { Text("Любой") })
                    TaskPriority.entries.reversed().forEach { option ->
                        FilterChip(selected = option.name == priority, onClick = { priority = option.name }, label = { Text(option.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(TaskFilter(TaskStatus.valueOf(status), priority?.let(TaskPriority::valueOf))) }) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(TaskFilter()) }) { Text("Сбросить") }
        },
    )
}
