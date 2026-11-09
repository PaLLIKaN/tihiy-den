package ru.tihiyden.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun timestamp(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm", Russian))

@Composable
fun ThoughtsScreen(thoughts: List<Thought>, onEdit: (Thought) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(thoughts, query) { thoughts.filter { it.text.contains(query.trim(), ignoreCase = true) } }
    Column {
        SearchField(query, { query = it }, "Найти мысль")
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item {
                EmptyState(Icons.Outlined.EditNote, if (query.isBlank()) "Пусть мысль останется" else "Ничего не нашлось",
                    if (query.isBlank()) "Идея, маленькое наблюдение\nили несколько слов о сегодняшнем дне." else "Попробуйте другие слова.")
            }
            items(filtered, key = { it.id }) { thought ->
                Surface(onClick = { onEdit(thought) }, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(thought.text, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        Text(timestamp(thought.createdAt), style = MaterialTheme.typography.labelSmall, color = Muted.copy(alpha = .75f), modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(media: List<MediaEntry>, kind: MediaKind, onKind: (MediaKind) -> Unit, onEdit: (MediaEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var statusName by rememberSaveable { mutableStateOf<String?>(null) }
    val status = statusName?.let { MediaStatus.valueOf(it) }
    val inKind = remember(media, kind) { media.filter { it.kind == kind } }
    val filtered = remember(inKind, status, query) {
        inKind.filter { (status == null || it.status == status) &&
            listOf(it.title, it.creator, it.thoughts).any { text -> text.contains(query.trim(), ignoreCase = true) } }
    }
    Column {
        PrimaryTabRow(selectedTabIndex = kind.ordinal, containerColor = Paper) {
            MediaKind.entries.forEach { item ->
                Tab(selected = item == kind, onClick = { onKind(item) }, text = { Text(item.label) })
            }
        }
        Spacer(Modifier.height(16.dp))
        SearchField(query, { query = it }, "Название, автор или мысль")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = status == null, onClick = { statusName = null }, label = { Text("Все · ${inKind.size}") })
            MediaStatus.entries.forEach { item ->
                FilterChip(selected = item == status, onClick = { statusName = if (item == status) null else item.name }, label = { Text(item.label(kind)) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item {
                EmptyState(mediaIcon(kind), if (query.isNotBlank() || status != null) "Здесь пока пусто" else "Ваша коллекция историй",
                    if (query.isNotBlank() || status != null) "Измените поиск или фильтр.\nНовые записи появятся здесь." else when (kind) {
                        MediaKind.BOOK -> "Сохраните книгу, которую хочется прочитать.\nА потом — мысли, которые она оставила."
                        MediaKind.MOVIE -> "Фильмы на вечер и впечатления\nпосле финальных титров."
                        MediaKind.SERIES -> "Следующая серия подождёт.\nЗапишите, что хочется посмотреть."
                    })
            }
            items(filtered, key = { it.id }) { item ->
                val image = rememberCoverImage(item.cover)
                val ratio = image?.let { it.width.toFloat() / it.height } ?: (2f / 3f)
                Surface(onClick = { onEdit(item) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    if (ratio > 1f) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            MediaCover(image, kind, Modifier.fillMaxWidth().aspectRatio(ratio))
                            MediaDetails(item)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MediaCover(image, kind, Modifier.size(width = 60.dp, height = (60f / ratio).coerceIn(48f, 120f).dp))
                            MediaDetails(item, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetails(item: MediaEntry, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.creator.isNotBlank()) Text(item.creator, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 3.dp))
        Text(item.status.label(item.kind), color = Sage, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        if (item.thoughts.isNotBlank()) Text(item.thoughts, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
    }
}

fun mediaIcon(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.BOOK -> Icons.Outlined.AutoStories
    MediaKind.MOVIE -> Icons.Outlined.Movie
    MediaKind.SERIES -> Icons.Outlined.LiveTv
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value = query, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Очистить поиск") } }) else null,
        singleLine = true, shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = Sage)
        }
        Text(title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        Text(subtitle, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
