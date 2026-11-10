package ru.tihiyden.diary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DiaryViewModel internal constructor(application: Application, private val store: DiaryStore) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, DiaryStore(application))
    private val mutex = Mutex()
    private val _data = MutableStateFlow(DiaryData())
    val data = _data.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy = _backupBusy.asStateFlow()
    private val _pendingRestore = MutableStateFlow<DiaryBackup?>(null)
    val pendingRestore = _pendingRestore.asStateFlow()
    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri = _shareUri.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _sharedCover = MutableStateFlow<SharedCover?>(null)
    internal val sharedCover = _sharedCover.asStateFlow()
    private var coverImport: Job? = null

    init { mutate { } }

    fun save(value: DayEntry, onSaved: () -> Unit) = mutate(onSaved) { save(value) }
    fun save(value: Thought, onSaved: () -> Unit) = mutate(onSaved) { save(value) }
    fun save(value: MediaEntry, onSaved: () -> Unit) = mutate(onSaved) { save(value) }
    fun toggle(value: DayEntry) = mutate { toggleEntry(value.id) }
    fun delete(value: DayEntry, onSaved: () -> Unit) = mutate(onSaved) { deleteEntry(value.id) }
    fun delete(value: Thought, onSaved: () -> Unit) = mutate(onSaved) { deleteThought(value.id) }
    fun delete(value: MediaEntry, onSaved: () -> Unit) = mutate(onSaved) { deleteMedia(value.id) }
    fun clearError() { _error.value = null }
    fun reportError(message: String) { _error.value = message }
    fun clearMessage() { _message.value = null }
    fun consumeShare() { _shareUri.value = null }
    fun cancelRestore() { if (!_backupBusy.value) _pendingRestore.value = null }

    internal fun importSharedCover(uri: Uri?) {
        dismissSharedCover()
        val application = getApplication<Application>()
        if (uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.authority == "${application.packageName}.backup-files") {
            _sharedCover.value = SharedCover.Error("Не удалось получить изображение. Сохраните его и выберите из галереи.")
            return
        }
        loadSharedCover(SharedCover.Error("Не удалось открыть изображение. Попробуйте другую картинку или выберите её из галереи.")) {
            SharedCover.Ready(CoverImages.importDraft(application, uri))
        }
    }

    internal fun offerSharedCoverLink(link: SharedCover.Link?) {
        dismissSharedCover()
        _sharedCover.value = link ?: SharedCover.Error("В отправленном тексте нет ссылки на изображение или страницу.")
    }

    internal fun downloadSharedCover(link: SharedCover.Link) {
        if (_sharedCover.value != link || link.pageOnly) return
        loadSharedCover(link.copy(message = "Не удалось загрузить картинку. Можно открыть страницу и выбрать кадр из галереи.")) {
            val draft = CoverSearch.importSharedImage(getApplication(), link.image)
            if (draft != null) SharedCover.Ready(draft)
            else link.copy(message = "По ссылке открывается страница. Сохраните нужный кадр в браузере и выберите его из галереи.", pageOnly = true)
        }
    }

    private fun loadSharedCover(failure: SharedCover, load: suspend () -> SharedCover) {
        _sharedCover.value = SharedCover.Loading
        coverImport = viewModelScope.launch {
            try { _sharedCover.value = withContext(Dispatchers.IO) { load() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _sharedCover.value = failure }
        }
    }

    internal fun dismissSharedCover() {
        coverImport?.cancel()
        coverImport = null
        _sharedCover.value = null
    }

    private suspend fun snapshot(): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock { BackupCodec.encode(store.read()) }
    }

    fun exportBackup(uri: Uri) = backupOperation("Не удалось сохранить копию. Проверьте доступ к выбранной папке и подключение к Диску.") {
        val bytes = snapshot()
        withContext(Dispatchers.IO) { BackupFiles.save(getApplication(), uri, bytes) }
        _message.value = "Копия сохранена в выбранном месте."
    }

    fun prepareShareBackup() = backupOperation("Не удалось подготовить резервную копию.") {
        val bytes = snapshot()
        _shareUri.value = withContext(Dispatchers.IO) { BackupFiles.prepareShare(getApplication(), bytes) }
    }

    fun inspectBackup(uri: Uri) = backupOperation("Не удалось открыть копию. Проверьте доступ к файлу и подключение к Диску.") {
        _pendingRestore.value = withContext(Dispatchers.IO) { BackupFiles.read(getApplication(), uri) }
    }

    fun restoreBackup(mode: RestoreMode) {
        val backup = _pendingRestore.value ?: return
        backupOperation("Не удалось восстановить копию. Текущие записи сохранены.") {
            val count = mutex.withLock {
                withContext(Dispatchers.IO) {
                    val result = store.restore(backup.data, mode)
                    _data.value = result.data
                    result.count
                }
            }
            _pendingRestore.value = null
            _message.value = if (mode == RestoreMode.REPLACE_ALL) "Копия восстановлена. Записей: $count."
                else "Добавлено записей: $count. Текущие записи сохранены."
        }
    }

    private fun backupOperation(errorMessage: String, action: suspend () -> Unit) {
        if (_backupBusy.value) return
        _backupBusy.value = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { _error.value = e.message ?: errorMessage }
            catch (e: Exception) { _error.value = errorMessage }
            finally { _backupBusy.value = false }
        }
    }

    private fun mutate(onSaved: () -> Unit = {}, action: DiaryStore.() -> Unit) {
        viewModelScope.launch {
            try {
                mutex.withLock {
                    _working.value = true
                    try {
                        _data.value = withContext(Dispatchers.IO) {
                            store.action()
                            store.read()
                        }
                        _ready.value = true
                    } finally {
                        _working.value = false
                    }
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Не удалось прочитать или сохранить данные. Попробуйте ещё раз."
            }
        }
    }
}
