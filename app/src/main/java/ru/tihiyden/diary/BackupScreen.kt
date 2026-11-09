package ru.tihiyden.diary

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BackupScreen(data: DiaryData, vm: DiaryViewModel) {
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()
    val shareUri by vm.shareUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFiles.MIME_TYPE)) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri)
    }
    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            vm.consumeShare()
            try { context.startActivity(Intent.createChooser(BackupFiles.shareIntent(uri), "Куда отправить копию?")) }
            catch (e: ActivityNotFoundException) { vm.reportError("Не найдено приложение для отправки файла. Сохраните копию через «Сохранить файл».") }
            catch (e: SecurityException) { vm.reportError("Не удалось передать файл. Попробуйте «Сохранить файл».") }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Всё важное — в одном файле", style = MaterialTheme.typography.titleMedium)
                Text(backupCounts(data), style = MaterialTheme.typography.bodyMedium)
                Text("Задачи, события, мысли и коллекция с обложками сохраняются вместе с датами, статусами и впечатлениями.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BackupAction(Icons.Outlined.SaveAlt, "Сохранить файл", "Выберите папку на телефоне или подключённый Диск в окне сохранения.", !busy) {
            try { save.launch(BackupFiles.suggestedName()) }
            catch (e: ActivityNotFoundException) { vm.reportError("На телефоне недоступно окно сохранения файлов. Попробуйте «Отправить копию».") }
        }
        BackupAction(Icons.Outlined.Share, "Отправить копию", "Выберите Яндекс Диск, Google Диск или другое приложение в меню отправки.", !busy, vm::prepareShareBackup)
        BackupAction(Icons.Outlined.Restore, "Восстановить из файла", "Выберите ранее сохранённую копию. Сначала покажем её содержимое и варианты восстановления.", !busy) {
            try { open.launch(arrayOf("*/*")) }
            catch (e: ActivityNotFoundException) { vm.reportError("На телефоне недоступно окно выбора файлов.") }
        }
        Text("Чтобы отправить копию на Диск, установите его приложение и войдите в свой аккаунт. Окно отправки открывается с готовым файлом; загрузку подтверждает приложение Диска.", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("Копия хранится без пароля и содержит личные записи. Сохраняйте её в своём аккаунте. Новую копию нужно создавать вручную после изменений.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    pending?.let { backup ->
        RestoreConfirmation(backup, busy, onDismiss = vm::cancelRestore, onRestore = vm::restoreBackup)
    }
}

fun backupCounts(data: DiaryData): String = "Дел: ${data.entries.size} · Мыслей: ${data.thoughts.size} · В коллекции: ${data.media.size}"

@Composable
private fun BackupAction(icon: ImageVector, title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = Sage, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun RestoreConfirmation(backup: DiaryBackup, busy: Boolean, onDismiss: () -> Unit, onRestore: (RestoreMode) -> Unit) {
    var modeName by rememberSaveable(backup.exportedAt.toString()) { mutableStateOf(RestoreMode.MERGE_MISSING.name) }
    val mode = RestoreMode.valueOf(modeName)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Восстановление копии") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Копия от " + timestamp(backup.exportedAt.toEpochMilli()))
                Text(backupCounts(backup.data))
                listOf(
                    Triple(RestoreMode.MERGE_MISSING, "Добавить недостающие", "Новые записи из копии добавятся. Уже имеющиеся записи и их последние изменения сохранятся."),
                    Triple(RestoreMode.REPLACE_ALL, "Заменить все данные", "Все текущие записи будут удалены и заменены содержимым этой копии."),
                ).forEach { (item, title, description) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { modeName = item.name }, verticalAlignment = Alignment.Top) {
                        RadioButton(selected = item == mode, onClick = { modeName = item.name }, enabled = !busy)
                        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (mode == RestoreMode.REPLACE_ALL) Text("Перед заменой сохраните свежую копию, если текущие записи могут понадобиться.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { onRestore(mode) }) { Text(if (busy) "Восстанавливаем…" else if (mode == RestoreMode.REPLACE_ALL) "Заменить данные" else "Добавить записи") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}
