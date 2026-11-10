package ru.tihiyden.diary

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.IntentCompat
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface SharedCover {
    data object Loading : SharedCover
    data class Ready(val reference: String) : SharedCover
    data class Error(val message: String) : SharedCover
    data class Link(val page: URI, val image: URI, val message: String? = null, val pageOnly: Boolean = false) : SharedCover
}

internal val sharedLinkMimeTypes = setOf("text/plain", "text/uri-list")
private val webUrlPattern = Regex("https?://[^\\s<>\"\\p{Cntrl}]+", RegexOption.IGNORE_CASE)
private val yandexImageHosts = setOf("yandex.ru", "www.yandex.ru", "yandex.com", "www.yandex.com", "ya.ru")

internal fun Intent.sharedCoverLink(): SharedCover.Link? {
    if (action != Intent.ACTION_SEND || type !in sharedLinkMimeTypes) return null
    return try {
        val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.let { it.uri?.toString() ?: it.text?.toString() }
            ?: return null
        val page = webUrlPattern.find(text.take(8192))?.value?.let(::sharedWebUri) ?: return null
        val image = if (page.host.lowercase(Locale.ROOT) in yandexImageHosts && page.path.orEmpty().startsWith("/images/")) {
            Uri.parse(page.toString()).getQueryParameter("img_url")?.let(::sharedWebUri)?.takeIf { it.scheme.equals("https", true) }
        } else null
        SharedCover.Link(page, image ?: page, pageOnly = image == null && !page.scheme.equals("https", true))
    } catch (_: RuntimeException) { null }
}

private fun sharedWebUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    val host = it.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return@takeIf false
    it.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") && it.userInfo == null && it.port == -1 &&
        host.contains('.') && host.any(Char::isLetter) && !host.endsWith(".local") && !host.endsWith(".localhost") && ':' !in host
}

@Composable
internal fun SharedCoverLinkDialog(link: SharedCover.Link, onDismiss: () -> Unit, onDownload: () -> Unit, onPick: (Uri) -> Unit) {
    val browser = LocalUriHandler.current
    var openFailed by remember(link) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPick) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ссылка из браузера") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(link.message ?: if (link.pageOnly) "Откройте страницу, сохраните нужный кадр и выберите его из галереи."
                    else "Если по ссылке открывается изображение, его можно загрузить как обложку. Для обычной страницы сначала сохраните нужный кадр.")
                if (!link.pageOnly) Text(link.image.host, color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openFailed = runCatching { browser.openUri(link.page.toString()) }.isFailure }) { Text("Открыть в браузере") }
                TextButton(onClick = {
                    openFailed = runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.isFailure
                }) { Text("Выбрать из галереи") }
                if (openFailed) Text("Не удалось открыть выбранное приложение.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { if (!link.pageOnly) TextButton(onClick = onDownload) { Text("Загрузить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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
