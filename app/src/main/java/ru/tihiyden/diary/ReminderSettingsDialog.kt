package ru.tihiyden.diary

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun ReminderSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(ReminderScheduler.readSettings(context)) }
    var allowed by remember { mutableStateOf(ReminderScheduler.canNotify(context)) }
    var exact by remember { mutableStateOf(ReminderScheduler.canScheduleExactly(context)) }
    var settingsError by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = ReminderScheduler.canNotify(context)
        ReminderScheduler.refresh(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = ReminderScheduler.canNotify(context)
        exact = ReminderScheduler.canScheduleExactly(context)
    }

    fun update(value: ReminderSettings) {
        settings = value
        ReminderScheduler.saveSettings(context, value)
    }

    fun open(intent: Intent) {
        settingsError = runCatching { context.startActivity(intent) }.isFailure
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Notifications, null) },
        title = { Text("Уведомления") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReminderSwitch("Сроки задач", settings.tasks) { update(settings.copy(tasks = it)) }
                if (settings.tasks) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "За 1 час", 2 to "За 2 часа").forEach { (hours, label) ->
                            FilterChip(selected = hours == settings.leadHours, onClick = { update(settings.copy(leadHours = hours)) },
                                label = { Text(label) })
                        }
                    }
                    Text("Напоминания приходят по срокам незавершённых задач. Если время срока не задано, используется $DefaultDueTime.",
                        style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= 31 && allowed && !exact) {
                        Text("Без доступа к точным напоминаниям Android может доставить уведомление позже.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
                        }) { Text("Разрешить точные напоминания") }
                    }
                }
                HorizontalDivider()
                ReminderSwitch("Совет на выходные", settings.weekend) { update(settings.copy(weekend = it)) }
                if (settings.weekend) Text("По субботам в 11:00 — одна книга, фильм или сериал из незавершённых.",
                    style = MaterialTheme.typography.bodySmall)
                if ((settings.tasks || settings.weekend) && !allowed) {
                    Text("Разрешите «Тихому дню» показывать уведомления в Android.", style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= 33) {
                        TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Разрешить уведомления") }
                    }
                    TextButton(onClick = {
                        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("Настройки Android") }
                }
                if (settingsError) Text("Не удалось открыть настройки Android.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun ReminderSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
