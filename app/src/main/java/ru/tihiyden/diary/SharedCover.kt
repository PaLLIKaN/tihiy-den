package ru.tihiyden.diary

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface SharedCover {
    data object Loading : SharedCover
    data class Ready(val reference: String) : SharedCover
    data class Error(val message: String) : SharedCover
}

internal fun Intent.sharedImageUri(): Uri? {
    if (action != Intent.ACTION_SEND || type?.startsWith("image/") != true) return null
    return try {
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            ?: clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
    } catch (_: RuntimeException) { null }
}

@Composable
internal fun SharedCoverDialog(
    reference: String,
    entries: List<MediaEntry>,
    openEditor: Boolean,
    defaultKind: MediaKind,
    saving: Boolean,
    onDismiss: () -> Unit,
    onUse: (MediaEntry?) -> Unit,
) {
    val context = LocalContext.current
    var loadFailed by remember(reference) { mutableStateOf(false) }
    val cover by produceState("", reference) {
        try { value = withContext(Dispatchers.IO) { CoverImages.readDraft(context, reference) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadFailed = true }
    }
    val image = rememberCoverImage(cover)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = entries.firstOrNull { it.id == selectedId }
    val filtered = remember(entries, query, choosing) {
        if (choosing) entries.filter { it.title.contains(query.trim(), ignoreCase = true) } else emptyList()
    }
    AlertDialog(
        onDismissRequest = { if (choosing) choosing = false else onDismiss() },
        modifier = Modifier.imePadding().systemBarsPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(if (choosing) "Выбрать запись" else "Обложка из другого приложения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choosing) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Найти по названию") }, singleLine = true)
                    if (filtered.isEmpty()) Text("Записи не найдены.")
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(filtered, key = { it.id }) { entry ->
                            Column(Modifier.fillMaxWidth().clickable { selectedId = entry.id; choosing = false }.padding(vertical = 10.dp)) {
                                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(entry.kind.label, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                } else {
                    when {
                        loadFailed -> Text("Картинка недоступна. Отправьте её ещё раз.", color = MaterialTheme.colorScheme.error)
                        image == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> MediaCover(image, selected?.kind ?: defaultKind, Modifier.fillMaxWidth().height(180.dp), preview = true)
                    }
                    if (openEditor) {
                        Text("Картинка будет добавлена в открытый редактор.")
                    } else {
                        Text(selected?.title ?: "Новая запись", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entries.isNotEmpty()) TextButton(onClick = { choosing = true }) { Text("Выбрать запись") }
                        if (selected != null) TextButton(onClick = { selectedId = null }) { Text("Создать новую запись") }
                    }
                }
            }
        },
        confirmButton = {
            if (!choosing) TextButton(enabled = image != null && !saving, onClick = { onUse(selected) }) { Text("Использовать") }
        },
        dismissButton = { TextButton(onClick = { if (choosing) choosing = false else onDismiss() }) { Text(if (choosing) "Назад" else "Отмена") } },
    )
}
