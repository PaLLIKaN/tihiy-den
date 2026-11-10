package ru.tihiyden.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.util.Base64
import kotlinx.coroutines.CancellationException

private data class CoverRequest(val title: String, val year: String, val attempt: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverSearchDialog(kind: MediaKind, initialTitle: String, onDismiss: () -> Unit, onUse: (String, ByteArray) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initialTitle.trim().take(COVER_QUERY_LIMIT)) }
    var year by rememberSaveable { mutableStateOf("") }
    var requestedTitle by rememberSaveable { mutableStateOf(title) }
    var requestedYear by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    val request = CoverRequest(requestedTitle, requestedYear, attempt)
    val isMovie = kind == MediaKind.MOVIE
    val hasBrowserSearch = kind != MediaKind.BOOK
    val validYear = year.isEmpty() || year.toIntOrNull()?.let { it in 1880..2099 } == true
    var candidates by remember(request) { mutableStateOf<List<CoverCandidate>?>(null) }
    var searchFailed by remember(request) { mutableStateOf(false) }
    var selectedMovieIndex by remember(request) { mutableIntStateOf(0) }
    var movieMenuExpanded by remember(request) { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var browserFailed by remember { mutableStateOf(false) }

    LaunchedEffect(kind, request) {
        if (request.title.isNotEmpty()) {
            try { candidates = CoverSearch.search(kind, request.title, request.year) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchFailed = true }
        }
    }
    val movie = if (isMovie) candidates?.getOrNull(selectedMovieIndex) else null
    var movieOptions by remember(movie) { mutableStateOf<List<CoverCandidate>?>(null) }
    LaunchedEffect(movie) {
        if (movie != null) movieOptions = CoverSearch.movieArtwork(movie)
    }
    val options = if (isMovie) movieOptions.orEmpty() else candidates.orEmpty()
    var selectedIndex by remember(request, movie) { mutableIntStateOf(0) }
    var expanded by remember(request, movie) { mutableStateOf(false) }
    val candidate = options.getOrNull(selectedIndex)
    var previewAttempt by remember(candidate) { mutableIntStateOf(0) }
    var preview by remember(candidate, previewAttempt) { mutableStateOf<ByteArray?>(null) }
    var previewFailed by remember(candidate, previewAttempt) { mutableStateOf(false) }
    LaunchedEffect(candidate, previewAttempt) {
        if (candidate != null) {
            try { preview = CoverSearch.download(context, candidate) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { previewFailed = true }
        }
    }
    val previewCover = remember(preview) { preview?.let { Base64.getEncoder().encodeToString(it) }.orEmpty() }
    val image = rememberCoverImage(previewCover)
    val pending = request.title.isNotEmpty() && !searchFailed && candidates == null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding().systemBarsPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("Подобрать обложку") },
        text = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val startSearch: () -> Unit = {
                if (title.isNotBlank() && validYear) {
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                    requestedTitle = title.trim()
                    requestedYear = year
                    attempt++
                }
            }
            Column(Modifier.heightIn(max = if (hasBrowserSearch) 530.dp else 485.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(COVER_QUERY_LIMIT) }, modifier = Modifier.weight(1f),
                        label = { Text(if (hasBrowserSearch) "Название" else "Название для поиска") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { startSearch() }))
                    if (hasBrowserSearch) {
                        OutlinedTextField(year, { year = it.filter { digit -> digit in '0'..'9' }.take(4) }, modifier = Modifier.width(86.dp),
                            label = { Text("Год") }, singleLine = true, isError = !validYear,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { startSearch() }))
                    }
                }
                val changedQuery = title.trim() != request.title || year != request.year
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = startSearch, enabled = title.isNotBlank() && validYear && (!pending || changedQuery)) { Text("Найти") }
                    if (hasBrowserSearch) {
                        TextButton(enabled = title.isNotBlank() && validYear, onClick = {
                            val match = (if (isMovie) movie else candidate)?.takeUnless { changedQuery }
                            val url = CoverSearch.yandexImagesUrl(match?.title ?: title, match?.releaseYear ?: year, kind)
                            browserFailed = runCatching { uriHandler.openUri(url) }.isFailure
                        }) { Text("Искать в браузере") }
                    }
                }
                if (browserFailed) Text("Не удалось открыть браузер.", color = MaterialTheme.colorScheme.error)
                when {
                    pending -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Ищем обложки…", style = MaterialTheme.typography.bodySmall)
                    }
                    searchFailed -> Text("Не удалось выполнить поиск. Проверьте интернет и попробуйте ещё раз.", color = MaterialTheme.colorScheme.error)
                    candidates?.isEmpty() == true -> Text("Обложка не найдена. Попробуйте другое или оригинальное название.")
                    request.title.isEmpty() -> Text("Введите название ${when (kind) { MediaKind.BOOK -> "книги"; MediaKind.MOVIE -> "фильма"; MediaKind.SERIES -> "сериала" }}.")
                }
                if (movie != null && candidates.orEmpty().size > 1) {
                    Box {
                        OutlinedButton(onClick = { movieMenuExpanded = true }) { Text("Фильм ${selectedMovieIndex + 1} из ${candidates.orEmpty().size}") }
                        DropdownMenu(expanded = movieMenuExpanded, onDismissRequest = { movieMenuExpanded = false }) {
                            candidates.orEmpty().forEachIndexed { index, film ->
                                DropdownMenuItem(text = { Text(film.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                                    selectedMovieIndex = index
                                    movieMenuExpanded = false
                                })
                            }
                        }
                    }
                }
                if (movie != null && movieOptions == null) {
                    Text(movie.displayTitle, style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Ищем широкие кадры…", style = MaterialTheme.typography.bodySmall)
                }
                if (candidate != null) {
                    HorizontalDivider()
                    if (options.size > 1) {
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text("Вариант ${selectedIndex + 1} из ${options.size}") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                options.forEachIndexed { index, option ->
                                    DropdownMenuItem(text = {
                                        Column {
                                            Text(if (isMovie) "${index + 1}. ${option.movieFormat()}" else option.displayTitle,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (!isMovie && option.description.isNotBlank()) {
                                                Text(option.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }, onClick = { selectedIndex = index; expanded = false })
                                }
                            }
                        }
                    }
                    Text(candidate.displayTitle, style = MaterialTheme.typography.titleSmall)
                    if (isMovie) {
                        Text(candidate.movieFormat(), style = MaterialTheme.typography.bodySmall, color = Sage)
                    }
                    if (!isMovie && candidate.description.isNotBlank()) Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                    when {
                        previewFailed -> {
                            Text("Картинка недоступна.", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { previewAttempt++ }) { Text("Повторить загрузку") }
                        }
                        image != null -> {
                            val size = if (isMovie && candidate.isWide) Modifier.aspectRatio(candidate.aspectRatio.toFloat().coerceIn(1.45f, 3f)) else Modifier.height(166.dp)
                            MediaCover(image, kind, Modifier.fillMaxWidth().then(size), preview = true)
                        }
                        else -> Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    }
                    if (isMovie && candidate.description.isNotBlank()) Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                    if (isMovie && options.none { it.isWide }) {
                        Text("Широких кадров в каталоге нет. Можно поискать их в браузере.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { runCatching { uriHandler.openUri(candidate.sourceUrl) } }) { Text(candidate.source) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = image != null, onClick = { preview?.let { onUse(request.title, it) } }) { Text("Использовать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun CoverCandidate.movieFormat(): String = if (isWide) "Широкая обложка" else "Постер"
