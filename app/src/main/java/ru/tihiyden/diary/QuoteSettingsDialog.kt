package ru.tihiyden.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun QuoteSettingsDialog(
    mode: QuoteMode,
    today: LocalDate,
    saving: Boolean,
    error: Boolean,
    onSelect: (QuoteMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val quote = remember(mode, today) { quoteForDay(today, mode) }
    val browser = LocalUriHandler.current
    var browserError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.FormatQuote, null) },
        title = { Text("Цитаты") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.selectableGroup()) {
                    QuoteMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(selected = mode == option, enabled = !saving,
                            role = Role.RadioButton, onClick = { onSelect(option) }).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = mode == option, onClick = null, enabled = !saving)
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(option.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Сегодня", style = MaterialTheme.typography.labelMedium, color = Muted)
                Text("«${quote.text}»", color = Sage)
                if (quote.author.isNotBlank()) {
                    Text(quote.author, style = MaterialTheme.typography.titleSmall)
                    Text(quote.work, style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (quote.translation.isNotBlank()) Text(quote.translation, style = MaterialTheme.typography.bodySmall, color = Muted)
                    TextButton(onClick = { browserError = runCatching { browser.openUri(quote.sourceUrl) }.isFailure }) {
                        Text("Открыть источник")
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Text("Не удалось сохранить выбор. Попробуйте ещё раз.", color = MaterialTheme.colorScheme.error)
                if (browserError) Text("Не удалось открыть браузер.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
