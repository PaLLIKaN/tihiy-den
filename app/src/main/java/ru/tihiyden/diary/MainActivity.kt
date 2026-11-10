package ru.tihiyden.diary

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val diary: DiaryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptShare(intent)
        setContent {
            val theme: ThemeViewModel = viewModel()
            val accent by theme.accent.collectAsStateWithLifecycle()
            val saving by theme.saving.collectAsStateWithLifecycle()
            val error by theme.error.collectAsStateWithLifecycle()
            DiaryTheme(accent, saving, theme::save) {
                DiaryApp(diary)
                if (error) AlertDialog(onDismissRequest = theme::clearError, title = { Text("Цветовая гамма") }, text = { Text("Не удалось сохранить цвет. Попробуйте ещё раз.") }, confirmButton = { TextButton(onClick = theme::clearError) { Text("Понятно") } })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShare(intent)
    }

    private fun acceptShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type in sharedLinkMimeTypes) diary.offerSharedCoverLink(intent.sharedCoverLink())
        else diary.importSharedCover(intent.sharedImageUri())
    }
}

enum class Section(val title: String, val subtitle: String, val icon: ImageVector) {
    CALENDAR("Календарь", "Место для важных дел", Icons.Outlined.CalendarMonth),
    THOUGHTS("Мысли", "Можно просто быть собой", Icons.Outlined.EditNote),
    MEDIA("Развлечения", "Истории, которые остаются", Icons.Outlined.AutoStories),
    BACKUP("Резервная копия", "Сохранить и перенести свои записи", Icons.Outlined.CloudUpload),
}

private enum class AppPanel { ABOUT, HOLIDAYS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryApp(vm: DiaryViewModel = viewModel()) {
    val holidayVm: HolidayViewModel = viewModel()
    val holidaySettings by holidayVm.settings.collectAsStateWithLifecycle()
    val holidays by holidayVm.state.collectAsStateWithLifecycle()
    val savingHolidays by holidayVm.saving.collectAsStateWithLifecycle()
    val holidaySettingsError by holidayVm.settingsError.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val sharedCover by vm.sharedCover.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val today by produceState(LocalDate.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { value = LocalDate.now(); delay(30_000) }
        }
    }
    var sectionName by rememberSaveable { mutableStateOf(Section.CALENDAR.name) }
    val section = Section.valueOf(sectionName)
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var mediaKindName by rememberSaveable { mutableStateOf(MediaKind.BOOK.name) }
    val mediaKind = MediaKind.valueOf(mediaKindName)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var entryEditor by rememberSaveable(stateSaver = DayEntrySaver) { mutableStateOf<DayEntry?>(null) }
    var thoughtEditor by rememberSaveable(stateSaver = ThoughtSaver) { mutableStateOf<Thought?>(null) }
    var mediaEditor by rememberSaveable(stateSaver = MediaEntrySaver) { mutableStateOf<MediaEntry?>(null) }
    var incomingCover by rememberSaveable { mutableStateOf<String?>(null) }
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var appPanel by rememberSaveable { mutableStateOf<AppPanel?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val calendarYear = LocalDate.parse(selectedDay).year
    val needsHolidays = section == Section.CALENDAR || appPanel == AppPanel.HOLIDAYS
    LaunchedEffect(calendarYear, needsHolidays, today, holidaySettings.enabled, holidaySettings.autoUpdate) {
        if (needsHolidays) holidayVm.loadYear(calendarYear)
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(32.dp))
                Icon(Icons.Outlined.Spa, null, Modifier.padding(horizontal = 28.dp).size(36.dp), tint = Sage)
                Text("Тихий день", Modifier.padding(start = 28.dp, top = 12.dp), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                Text("Дела, мысли и любимые истории", Modifier.padding(start = 28.dp, top = 8.dp, bottom = 24.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                ThemeMenuItem { themePicker = true }
                Spacer(Modifier.height(16.dp))
                Section.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) }, selected = item == section,
                        onClick = { sectionName = item.name; deleteId = null; scope.launch { drawer.close() } },
                        icon = { Icon(item.icon, null) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("В своём ритме.", Modifier.padding(28.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("ТИХИЙ ДЕНЬ", style = MaterialTheme.typography.labelMedium, color = Muted) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Открыть меню") } },
                    actions = {
                        IconButton(onClick = { appPanel = AppPanel.ABOUT }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Outlined.Spa, "О приложении", Modifier.size(22.dp), tint = Sage)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                val actionLabel = when (section) { Section.CALENDAR -> "Добавить дело"; Section.THOUGHTS -> "Записать мысль"; Section.MEDIA -> "Добавить"; Section.BACKUP -> "" }
                if (ready && section != Section.BACKUP) ExtendedFloatingActionButton(
                    modifier = Modifier.semantics { contentDescription = actionLabel },
                    onClick = {
                        when (section) {
                            Section.CALENDAR -> entryEditor = DayEntry(date = LocalDate.parse(selectedDay))
                            Section.THOUGHTS -> thoughtEditor = Thought()
                            Section.MEDIA -> mediaEditor = MediaEntry(kind = mediaKind)
                            Section.BACKUP -> Unit
                        }
                    },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(actionLabel) },
                    containerColor = Sage, contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 20.dp)) {
                    Text(section.title, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.height(6.dp))
                    if (section == Section.CALENDAR) {
                        Text("«${quoteForDay(today)}»", style = MaterialTheme.typography.bodyMedium, color = Sage)
                        Text("Цитата дня · " + today.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", Russian)),
                            style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 5.dp))
                    } else Text(section.subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
                if (!ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                } else when (section) {
                    Section.CALENDAR -> CalendarScreen(
                        data.entries, LocalDate.parse(selectedDay), { selectedDay = it.toString() }, { entryEditor = it },
                        vm::toggle, holidays, holidaySettings, onDeleteRequest = { deleteId = it.id },
                    )
                    Section.THOUGHTS -> ThoughtsScreen(data.thoughts, onEdit = { thoughtEditor = it }, onDeleteRequest = { deleteId = it.id })
                    Section.MEDIA -> MediaScreen(data.media, mediaKind, { mediaKindName = it.name }, onEdit = { mediaEditor = it }, onDeleteRequest = { deleteId = it.id })
                    Section.BACKUP -> BackupScreen(data, vm)
                }
            }
        }
    }

    if (ready) deleteId?.let { id ->
        val deletion = when (section) {
            Section.CALENDAR -> data.entries.firstOrNull { it.id == id }?.let { entry ->
                RecordDeletion(
                    question = if (entry.kind == EntryKind.TASK) "Удалить задачу?" else "Удалить событие?",
                    preview = entry.title,
                    delete = { vm.delete(entry) {} },
                    spansDays = entry.lastDay > entry.date,
                )
            }
            Section.THOUGHTS -> data.thoughts.firstOrNull { it.id == id }?.let { thought ->
                RecordDeletion("Удалить мысль?", thought.text, delete = { vm.delete(thought) {} })
            }
            Section.MEDIA -> data.media.firstOrNull { it.id == id }?.let { item ->
                val question = when (item.kind) {
                    MediaKind.BOOK -> "Удалить книгу?"
                    MediaKind.MOVIE -> "Удалить фильм?"
                    MediaKind.SERIES -> "Удалить сериал?"
                }
                RecordDeletion(question, item.title, delete = { vm.delete(item) {} })
            }
            Section.BACKUP -> null
        }
        if (deletion != null) RecordDeletionDialog(deletion, !working, onDismiss = { deleteId = null }, onConfirm = {
            if (deleteId == id) {
                deleteId = null
                deletion.delete()
            }
        })
    }

    if (themePicker) ThemePicker { themePicker = false }
    when (appPanel) {
        AppPanel.ABOUT -> AboutAppDialog(onHolidays = { appPanel = AppPanel.HOLIDAYS }, onDismiss = { appPanel = null })
        AppPanel.HOLIDAYS -> HolidaySettingsDialog(
            holidaySettings, holidays, savingHolidays, holidayVm::saveSettings,
            onRefresh = { holidayVm.loadYear(calendarYear, force = true) },
            onDismiss = { appPanel = AppPanel.ABOUT },
        )
        null -> Unit
    }
    if (holidaySettingsError) AlertDialog(onDismissRequest = holidayVm::clearSettingsError, title = { Text("Праздники России") }, text = { Text("Не удалось сохранить настройки. Попробуйте ещё раз.") }, confirmButton = { TextButton(onClick = holidayVm::clearSettingsError) { Text("Понятно") } })
    entryEditor?.let { entry ->
        EntryEditor(entry, saving = working, onDismiss = { entryEditor = null },
            onSave = { saved -> vm.save(saved) {
                if (!saved.occursOn(LocalDate.parse(selectedDay))) selectedDay = saved.date.toString()
                entryEditor = null
            } },
            onDelete = if (data.entries.any { it.id == entry.id }) ({ vm.delete(entry) { entryEditor = null } }) else null)
    }
    thoughtEditor?.let { thought ->
        ThoughtEditor(thought, saving = working, onDismiss = { thoughtEditor = null },
            onSave = { vm.save(it) { thoughtEditor = null } },
            onDelete = if (data.thoughts.any { it.id == thought.id }) ({ vm.delete(thought) { thoughtEditor = null } }) else null)
    }
    if (ready) mediaEditor?.let { media ->
        // The database supplies the original cover after recreation; images never enter the saved-state Bundle.
        val original = data.media.firstOrNull { it.id == media.id } ?: media
        val closeEditor = { mediaEditor = null; incomingCover = null }
        MediaEditor(original, saving = working, onDismiss = closeEditor,
            onSave = { vm.save(it, closeEditor) },
            onDelete = if (data.media.any { it.id == media.id }) ({ vm.delete(media, closeEditor) }) else null,
            incomingCover = incomingCover, onCoverApplied = { incomingCover = null })
    }
    when (val share = sharedCover) {
        SharedCover.Loading -> AlertDialog(onDismissRequest = vm::dismissSharedCover,
            title = { Text("Открываем изображение…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = vm::dismissSharedCover) { Text("Отмена") } })
        is SharedCover.Error -> AlertDialog(onDismissRequest = vm::dismissSharedCover,
            title = { Text("Обложка") }, text = { Text(share.message) },
            confirmButton = { TextButton(onClick = vm::dismissSharedCover) { Text("Понятно") } })
        is SharedCover.Link -> SharedCoverLinkDialog(share, vm::dismissSharedCover, { vm.downloadSharedCover(share) }, vm::importSharedCover)
        is SharedCover.Ready -> if (ready) key(share.reference) {
            val defaultKind = if (section == Section.MEDIA) mediaKind else MediaKind.MOVIE
            SharedCoverDialog(share.reference, data.media, mediaEditor != null, defaultKind, working, vm::dismissSharedCover) { selected ->
                if (mediaEditor == null) {
                    val item = selected ?: MediaEntry(kind = defaultKind)
                    sectionName = Section.MEDIA.name
                    mediaKindName = item.kind.name
                    mediaEditor = item
                }
                incomingCover = share.reference
                vm.dismissSharedCover()
            }
        }
        null -> Unit
    }
    if (error != null) AlertDialog(onDismissRequest = vm::clearError,
        title = { Text("Не удалось выполнить действие") }, text = { Text(error.orEmpty()) },
        confirmButton = { TextButton(onClick = vm::clearError) { Text("Понятно") } })
}

@Composable
private fun AboutAppDialog(onHolidays: () -> Unit, onDismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    var browserError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Spa, null, tint = Sage) },
        title = { Text("Тихий день") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Версия ${stringResource(R.string.app_version)}", style = MaterialTheme.typography.labelMedium, color = Muted)
                Text("Личный календарь для событий и задач, мыслей и списка книг, фильмов и сериалов.")
                TextButton(onClick = {
                    browserError = runCatching { browser.openUri("https://github.com/PaLLIKaN/tihiy-den") }.isFailure
                }) { Text("Репозиторий на GitHub") }
                if (browserError) Text("Не удалось открыть браузер.", color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                OutlinedButton(onClick = onHolidays) {
                    Icon(Icons.Outlined.EventAvailable, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Настройки праздников")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
