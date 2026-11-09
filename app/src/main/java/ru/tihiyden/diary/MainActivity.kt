package ru.tihiyden.diary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme: ThemeViewModel = viewModel()
            val accent by theme.accent.collectAsStateWithLifecycle()
            val saving by theme.saving.collectAsStateWithLifecycle()
            val error by theme.error.collectAsStateWithLifecycle()
            DiaryTheme(accent, saving, theme::save) {
                DiaryApp()
                if (error) AlertDialog(onDismissRequest = theme::clearError, title = { Text("Цветовая гамма") }, text = { Text("Не удалось сохранить цвет. Попробуйте ещё раз.") }, confirmButton = { TextButton(onClick = theme::clearError) { Text("Понятно") } })
            }
        }
    }
}

enum class Section(val title: String, val subtitle: String, val icon: ImageVector) {
    CALENDAR("Календарь", "Место для важных дел", Icons.Outlined.CalendarMonth),
    THOUGHTS("Мысли", "Можно просто быть собой", Icons.Outlined.EditNote),
    MEDIA("Развлечения", "Истории, которые остаются", Icons.Outlined.AutoStories),
    BACKUP("Резервная копия", "Сохранить и перенести свои записи", Icons.Outlined.CloudUpload),
}

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
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var holidayPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedDay, section, today, holidaySettings) {
        if (section == Section.CALENDAR) holidayVm.loadYear(LocalDate.parse(selectedDay).year)
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
                        onClick = { sectionName = item.name; scope.launch { drawer.close() } },
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
                    actions = { Icon(Icons.Outlined.Spa, null, Modifier.padding(end = 22.dp).size(22.dp), tint = Sage) },
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
                    Section.CALENDAR -> CalendarScreen(data.entries, LocalDate.parse(selectedDay), { selectedDay = it.toString() }, { entryEditor = it }, vm::toggle, holidays, holidaySettings) { holidayPicker = true }
                    Section.THOUGHTS -> ThoughtsScreen(data.thoughts) { thoughtEditor = it }
                    Section.MEDIA -> MediaScreen(data.media, mediaKind, { mediaKindName = it.name }) { mediaEditor = it }
                    Section.BACKUP -> BackupScreen(data, vm)
                }
            }
        }
    }

    if (themePicker) ThemePicker { themePicker = false }
    if (holidayPicker) HolidaySettingsDialog(holidaySettings, holidays, savingHolidays, holidayVm::saveSettings, { holidayVm.loadYear(LocalDate.parse(selectedDay).year, force = true) }) { holidayPicker = false }
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
        MediaEditor(original, saving = working, onDismiss = { mediaEditor = null },
            onSave = { vm.save(it) { mediaEditor = null } },
            onDelete = if (data.media.any { it.id == media.id }) ({ vm.delete(media) { mediaEditor = null } }) else null)
    }
    if (error != null) AlertDialog(onDismissRequest = vm::clearError,
        title = { Text("Не удалось выполнить действие") }, text = { Text(error.orEmpty()) },
        confirmButton = { TextButton(onClick = vm::clearError) { Text("Понятно") } })
}
