package ru.tihiyden.diary

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorFrame(title: String, valid: Boolean, dirty: Boolean, saving: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    var discard by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val exit = { if (dirty) discard = true else onDismiss() }
    Dialog(onDismissRequest = { if (!saving) exit() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (!saving) exit() }
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = exit, enabled = !saving) { Icon(Icons.Outlined.Close, "Закрыть") } },
                    actions = { TextButton(enabled = valid && !saving, onClick = onSave) { Text(if (saving) "Сохраняем…" else "Сохранить") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                content()
                if (onDelete != null) TextButton(onClick = { delete = true }, enabled = !saving, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Outlined.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить запись")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Закрыть без сохранения?") }, text = { Text("Изменения в этой записи будут потеряны.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Продолжить запись") } })
        if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Удалить запись?") }, text = { Text("Восстановить её не получится.") },
            confirmButton = { TextButton(onClick = { delete = false; onDelete?.invoke() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Оставить") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryEditor(entry: DayEntry, saving: Boolean, onDismiss: () -> Unit, onSave: (DayEntry) -> Unit, onDelete: (() -> Unit)?) {
    var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
    var details by rememberSaveable(entry.id) { mutableStateOf(entry.details) }
    var dateText by rememberSaveable(entry.id) { mutableStateOf(entry.date.toString()) }
    var time by rememberSaveable(entry.id) { mutableStateOf(entry.time) }
    var kindName by rememberSaveable(entry.id) { mutableStateOf(entry.kind.name) }
    var priorityName by rememberSaveable(entry.id) { mutableStateOf(entry.priority.name) }
    var dueDateText by rememberSaveable(entry.id) { mutableStateOf(entry.dueDate?.toString()) }
    val date = LocalDate.parse(dateText)
    val kind = EntryKind.valueOf(kindName)
    val isTask = kind == EntryKind.TASK
    val priority = if (isTask) TaskPriority.valueOf(priorityName) else TaskPriority.NORMAL
    val dueDate = if (isTask) dueDateText?.let(LocalDate::parse) else null
    val validDueDate = dueDate == null || dueDate >= date
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", Russian) }
    val context = LocalContext.current
    val dirty = title != entry.title || details != entry.details || date != entry.date || time != entry.time || kind != entry.kind ||
        priority != entry.priority || dueDate != entry.dueDate
    EditorFrame(if (onDelete == null) "Новое дело" else "Ваше дело", title.isNotBlank() && validDueDate, dirty, saving, onDismiss,
        { onSave(entry.copy(title = title.trim(), details = details.trim(), date = date, time = time, kind = kind,
            done = isTask && entry.done, priority = priority, dueDate = dueDate)) }, onDelete) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EntryKind.entries.forEach { item -> FilterChip(selected = item == kind, onClick = { kindName = item.name }, label = { Text(item.label) }) }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = {
            DatePickerDialog(context, { _, year, month, day -> dateText = LocalDate.of(year, month + 1, day).toString() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.CalendarMonth, null)
            Spacer(Modifier.width(10.dp))
            Text(if (isTask) "Начало: ${date.format(dateFormat)}" else date.format(dateFormat))
        }
        if (isTask) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val initial = maxOf(date, dueDate ?: date)
                        DatePickerDialog(context, { _, year, month, day -> dueDateText = LocalDate.of(year, month + 1, day).toString() },
                            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                            datePicker.minDate = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.show()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.EventAvailable, null)
                        Spacer(Modifier.width(10.dp))
                        Text(dueDate?.let { "Срок: ${it.format(dateFormat)}" } ?: "Указать срок выполнения")
                    }
                    if (dueDate != null) IconButton(onClick = { dueDateText = null }) { Icon(Icons.Outlined.Close, "Убрать срок") }
                }
                Text(if (validDueDate) "Задача видна с даты начала по срок включительно." else "Срок не может быть раньше даты начала.",
                    style = MaterialTheme.typography.bodySmall, color = if (validDueDate) Muted else MaterialTheme.colorScheme.error)
            }
            Column {
                Text("Приоритет", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskPriority.entries.reversed().forEach { option ->
                        FilterChip(selected = option == priority, onClick = { priorityName = option.name }, label = { Text(option.label) })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val initial = if (time.isBlank()) LocalTime.now() else LocalTime.parse(time)
                TimePickerDialog(context, { _, hour, minute -> time = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute) }, initial.hour, initial.minute, true).show()
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Schedule, null)
                Spacer(Modifier.width(10.dp))
                Text(time.ifBlank { "Без времени" })
            }
            if (time.isNotBlank()) IconButton(onClick = { time = "" }) { Icon(Icons.Outlined.Close, "Убрать время") }
        }
        OutlinedTextField(details, { details = it }, label = { Text("Подробности") }, placeholder = { Text("Что хочется не забыть?") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
    }
}

@Composable
fun ThoughtEditor(thought: Thought, saving: Boolean, onDismiss: () -> Unit, onSave: (Thought) -> Unit, onDelete: (() -> Unit)?) {
    var text by rememberSaveable(thought.id) { mutableStateOf(thought.text) }
    EditorFrame(if (onDelete == null) "Новая мысль" else "Ваша мысль", text.isNotBlank(), text != thought.text, saving, onDismiss,
        { onSave(thought.copy(text = text.trim(), updatedAt = System.currentTimeMillis())) }, onDelete) {
        Text(timestamp(thought.createdAt), style = MaterialTheme.typography.labelSmall, color = Muted.copy(alpha = .75f))
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("О чём вы сейчас думаете?") }, minLines = 10)
        VoiceInput { text = appendDictation(text, it) }
    }
}

@Composable
fun MediaEditor(media: MediaEntry, saving: Boolean, onDismiss: () -> Unit, onSave: (MediaEntry) -> Unit, onDelete: (() -> Unit)?, incomingCover: String? = null, onCoverApplied: () -> Unit = {}) {
    var title by rememberSaveable(media.id) { mutableStateOf(media.title) }
    var creator by rememberSaveable(media.id) { mutableStateOf(media.creator) }
    var thoughts by rememberSaveable(media.id) { mutableStateOf(media.thoughts) }
    var kindName by rememberSaveable(media.id) { mutableStateOf(media.kind.name) }
    var statusName by rememberSaveable(media.id) { mutableStateOf(media.status.name) }
    // Only this small reference enters saved state, even when both old and new covers are large.
    var coverReference by rememberSaveable(media.id) { mutableStateOf<String?>(null) }
    var showCoverSearch by rememberSaveable(media.id) { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var coverError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(incomingCover, importing, saving) {
        if (incomingCover != null && !importing && !saving) {
            coverReference = incomingCover
            showCoverSearch = false
            onCoverApplied()
        }
    }
    val resolvedCover by produceState<Pair<String?, String>?>(if (coverReference == null) null to media.cover else null, coverReference, media.cover) {
        try {
            val reference = coverReference
            val cover = when (reference) {
                null -> media.cover
                "" -> ""
                else -> withContext(Dispatchers.IO) { CoverImages.readDraft(context, reference) }
            }
            value = reference to cover
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            coverError = "Черновик обложки недоступен. Выберите картинку ещё раз."
            coverReference = null
        }
    }
    val cover = resolvedCover?.second ?: media.cover
    val coverImage = rememberCoverImage(cover)
    val coverRatio = coverImage?.let { it.width.toFloat() / it.height } ?: (2f / 3f)
    val coverReady = resolvedCover != null && resolvedCover?.first == coverReference && !importing
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try { coverReference = withContext(Dispatchers.IO) { CoverImages.importDraft(context, uri) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { coverError = if (e.message == "Выберите изображение меньше 64 МБ.") e.message else "Не удалось открыть картинку. Попробуйте другое изображение." }
            finally { importing = false }
        }
    }
    val kind = MediaKind.valueOf(kindName)
    val status = MediaStatus.valueOf(statusName)
    val dirty = title != media.title || creator != media.creator || thoughts != media.thoughts || kind != media.kind || status != media.status || coverReference != null
    EditorFrame(if (onDelete == null) "В коллекцию" else "Ваша история", title.isNotBlank() && coverReady, dirty, saving, onDismiss,
        { onSave(media.copy(title = title.trim(), creator = creator.trim(), thoughts = thoughts.trim(), kind = kind, status = status, updatedAt = System.currentTimeMillis(), cover = cover)) }, onDelete) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKind.entries.forEach { item -> FilterChip(selected = item == kind, onClick = { kindName = item.name }, label = { Text(item.label) }) }
        }
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
        val coverActions: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Обложка", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(enabled = coverReady && !saving, onClick = { showCoverSearch = true }) { Text("Подобрать обложку") }
                Text("Выберите картинку из галереи", style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(enabled = coverReady && !saving, onClick = {
                    try { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { coverError = "Не удалось открыть выбор изображений." }
                }) { Text(if (!coverReady) "Загружаем…" else if (cover.isEmpty()) "Добавить" else "Заменить") }
                if (cover.isNotEmpty()) TextButton(enabled = coverReady && !saving, onClick = { coverReference = "" }) { Text("Убрать обложку") }
            }
        }
        if (coverRatio > 1f) {
            MediaCover(coverImage, kind, Modifier.fillMaxWidth().aspectRatio(coverRatio), preview = true)
            coverActions()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MediaCover(coverImage, kind, Modifier.size(width = 104.dp, height = (104f / coverRatio).coerceIn(72f, 180f).dp), preview = true)
                Column(Modifier.weight(1f)) { coverActions() }
            }
        }
        OutlinedTextField(creator, { creator = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (kind == MediaKind.BOOK) "Автор · необязательно" else "Режиссёр / создатель · необязательно") }, singleLine = true)
        Text("Где вы сейчас", style = MaterialTheme.typography.titleSmall)
        Column {
            MediaStatus.entries.forEach { item ->
                FilterChip(selected = item == status, onClick = { statusName = item.name }, label = { Text(item.label(kind)) },
                    leadingIcon = if (item == status) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
            }
        }
        OutlinedTextField(thoughts, { thoughts = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Мысли и впечатления") }, placeholder = { Text("Что запомнилось? К чему хочется вернуться?") }, minLines = 6)
        VoiceInput { thoughts = appendDictation(thoughts, it) }
    }
    if (showCoverSearch) CoverSearchDialog(kind, title, onDismiss = { showCoverSearch = false }, onUse = { query, bytes ->
        showCoverSearch = false
        importing = true
        scope.launch {
            try {
                coverReference = withContext(Dispatchers.IO) { CoverImages.writeDraft(context, bytes) }
                if (title.isBlank()) title = query
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { coverError = "Не удалось сохранить выбранную обложку." }
            finally { importing = false }
        }
    })
    if (coverError != null) AlertDialog(onDismissRequest = { coverError = null }, title = { Text("Обложка") }, text = { Text(coverError.orEmpty()) }, confirmButton = { TextButton(onClick = { coverError = null }) { Text("Понятно") } })
}

fun appendDictation(current: String, recognized: String): String =
    if (current.isBlank()) recognized.trim() else current.trimEnd() + "\n" + recognized.trim()

@Composable
fun VoiceInput(onResult: (String) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val latestResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) latestResult(spoken) else error = "Не удалось разобрать речь. Попробуйте ещё раз."
        }
    }
    OutlinedButton(onClick = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Расскажите, что хочется записать")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { launcher.launch(intent) }
        catch (e: ActivityNotFoundException) { error = "На телефоне нет службы распознавания речи. Можно установить её или ввести текст вручную." }
        catch (e: SecurityException) { error = "Служба распознавания речи недоступна. Проверьте её разрешение на микрофон в настройках телефона." }
    }) {
        Icon(Icons.Outlined.MicNone, null)
        Spacer(Modifier.width(8.dp))
        Text("Продиктовать")
    }
    Text("Речь превратится в текст — его можно будет поправить перед сохранением.", style = MaterialTheme.typography.bodySmall, color = Muted)
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Голосовой ввод") }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } })
}
