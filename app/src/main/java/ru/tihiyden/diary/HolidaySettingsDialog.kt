package ru.tihiyden.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun HolidaySettingsDialog(settings: HolidaySettings, state: HolidayUiState, saving: Boolean, onChange: (HolidaySettings) -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.EventAvailable, null) },
        title = { Text("Праздники России") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HolidaySwitch("Показывать праздники", settings.enabled, !saving) { onChange(settings.copy(enabled = it)) }
                HolidaySwitch("Государственные", settings.public, settings.enabled && !saving) { onChange(settings.copy(public = it)) }
                HolidaySwitch("Памятные даты", settings.memorial, settings.enabled && !saving) { onChange(settings.copy(memorial = it)) }
                HolidaySwitch("Профессиональные", settings.professional, settings.enabled && !saving) { onChange(settings.copy(professional = it)) }
                HolidaySwitch("Переносы и сокращённые дни", settings.dayTypes, settings.enabled && !saving) { onChange(settings.copy(dayTypes = it)) }
                HorizontalDivider()
                HolidaySwitch("Автообновление календаря", settings.autoUpdate, !saving) { onChange(settings.copy(autoUpdate = it)) }
                Text("Проверяем переносы при открытии календаря, если с последнего успешного обновления прошла неделя. Данные сохраняются на телефоне.", style = MaterialTheme.typography.bodySmall)
                Text(when {
                    !state.loaded -> "Открываем календарь на ${state.year} год…"
                    state.calendar == null -> "Переносы на ${state.year} год ещё не загружены."
                    state.downloadedAt == 0L -> "Встроенный производственный календарь на ${state.year} год."
                    else -> "Календарь на ${state.year} год обновлён: ${timestamp(state.downloadedAt)}."
                }, style = MaterialTheme.typography.bodySmall)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error != null) Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(enabled = !state.loading, onClick = onRefresh) { Text("Обновить сейчас") }
                Text("Памятные и профессиональные даты рассчитываются автоматически по встроенному справочнику от ${HolidayRules.REVIEWED}. Изменения справочника приходят с обновлениями приложения. Эти даты сами по себе не означают выходной день.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { runCatching { uriHandler.openUri("https://xmlcalendar.ru/") } }) { Text("Источник переносов — XMLCalendar") }
                TextButton(onClick = { runCatching { uriHandler.openUri("https://www.consultant.ru/document/cons_doc_LAW_19238/") } }) { Text("Источники памятных и профессиональных дат") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun HolidaySwitch(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
