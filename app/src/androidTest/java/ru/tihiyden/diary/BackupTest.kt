package ru.tihiyden.diary

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): DiaryData = DiaryData(
        entries = listOf(
            DayEntry(id = "task", title = "Задача 📚", details = "Строка 1\n\"Строка 2\"", date = LocalDate.of(2028, 2, 29), time = "23:59", done = true,
                priority = TaskPriority.HIGH, dueDate = LocalDate.of(2028, 3, 2)),
            DayEntry(id = "event", title = "Встреча", date = LocalDate.of(2026, 10, 27), kind = EntryKind.EVENT),
        ),
        thoughts = listOf(Thought("note", "Мысль\nс новой строкой и ё", 1234, 9876)),
        media = listOf(
            MediaEntry("book", "Книга", "Автор", MediaKind.BOOK, MediaStatus.DONE, "Впечатления 🎉", 12345),
            MediaEntry("movie", "Фильм", "Режиссёр", MediaKind.MOVIE, MediaStatus.PLANNED, "Посмотреть", 23456),
            MediaEntry("series", "Сериал", "Создатель", MediaKind.SERIES, MediaStatus.IN_PROGRESS, "Сезон 2", 34567),
        ),
    )
    private fun assertData(expected: DiaryData, actual: DiaryData) {
        assertEquals(expected.entries.toSet(), actual.entries.toSet())
        assertEquals(expected.thoughts.toSet(), actual.thoughts.toSet())
        assertEquals(expected.media.toSet(), actual.media.toSet())
    }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid backup to be rejected") } catch (_: IllegalArgumentException) { }
    }
    private fun withStore(action: (DiaryStore, String) -> Unit) {
        val name = "backup_test_${UUID.randomUUID()}.db"
        try { DiaryStore(context, name).use { action(it, name) } }
        finally { context.deleteDatabase(name) }
    }

    @Test fun allFieldsSurviveUtf8FileRoundTrip() {
        val exported = Instant.parse("2026-10-27T00:00:00Z")
        val bytes = BackupCodec.encode(fixture(), exported)
        val restored = BackupCodec.read(ByteArrayInputStream(bytes))
        assertEquals(exported, restored.exportedAt)
        assertData(fixture(), restored.data)
        assertData(DiaryData(), BackupCodec.decode(BackupCodec.encode(DiaryData())).data)
    }

    @Test fun invalidFilesAndUnsupportedVersionsAreRejected() {
        val original = BackupCodec.encode(fixture()).toString(Charsets.UTF_8)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 999) },
            { it.put("format", "another.app") },
            { it.remove("thoughts") },
            { it.getJSONArray("entries").getJSONObject(0).put("date", "2027-02-29") },
            { it.getJSONArray("entries").getJSONObject(0).put("time", "24:00") },
            { it.getJSONArray("entries").getJSONObject(0).put("kind", "UNKNOWN") },
            { it.getJSONArray("entries").getJSONObject(0).put("done", "true") },
            { it.getJSONArray("entries").getJSONObject(0).put("priority", "UNKNOWN") },
            { it.getJSONArray("entries").getJSONObject(0).put("dueDate", "2028-02-28") },
            { it.getJSONArray("entries").getJSONObject(1).put("dueDate", "2026-10-28") },
            { it.getJSONArray("entries").getJSONObject(0).put("title", 123) },
            { it.getJSONArray("entries").put(it.getJSONArray("entries").getJSONObject(0)) },
            { it.getJSONArray("thoughts").getJSONObject(0).put("text", " ") },
            { it.getJSONArray("thoughts").getJSONObject(0).put("createdAt", 1.25) },
            { it.getJSONArray("media").getJSONObject(0).put("status", "UNKNOWN") },
            { it.put("exportedAt", "+999999999-01-01T00:00:00Z") },
        )
        mutations.forEach { mutate ->
            val root = JSONObject(original)
            mutate(root)
            invalid { BackupCodec.decode(root.toString().toByteArray(Charsets.UTF_8)) }
        }
        invalid { BackupCodec.decode((original + " trailing garbage").toByteArray()) }
        invalid { BackupCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        invalid { BackupCodec.decode("Not a backup".toByteArray()) }
    }

    @Test fun oversizedFileIsRejectedBeforeParsing() {
        val bytes = ByteArray(BackupCodec.MAX_BYTES + 1)
        invalid { BackupCodec.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun mergeKeepsCurrentEditsAndDoesNotDuplicateRepeatedImports() = withStore { store, _ ->
        val current = fixture().entries.first().copy(title = "Моя новая редакция")
        store.save(current)
        val first = store.restore(fixture(), RestoreMode.MERGE_MISSING)
        assertEquals(5, first.count)
        assertEquals(current, first.data.entries.single { it.id == current.id })
        assertEquals(0, store.restore(fixture(), RestoreMode.MERGE_MISSING).count)
        assertEquals(2, store.read().entries.size)
        assertEquals(3, store.read().media.size)
    }

    @Test fun fullRestoreReplacesAllTablesAndSurvivesReopening() = withStore { store, name ->
        store.save(DayEntry(id = "old", title = "Старая запись"))
        store.save(Thought(id = "old-note", text = "Другая мысль"))
        store.save(MediaEntry(id = "old-book", title = "Другая книга"))
        val result = store.restore(BackupCodec.decode(BackupCodec.encode(fixture())).data, RestoreMode.REPLACE_ALL)
        assertEquals(6, result.count)
        assertData(fixture(), result.data)
        DiaryStore(context, name).use { reopened -> assertData(fixture(), reopened.read()) }
    }

    @Test fun failedRestoreRollsBackDeletesAndPartialInserts() = withStore { store, _ ->
        val original = DiaryData(entries = listOf(DayEntry(id = "original", title = "Не потерять")), thoughts = listOf(Thought(id = "original-note", text = "Сохранить")))
        original.entries.forEach(store::save)
        original.thoughts.forEach(store::save)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_test_note BEFORE INSERT ON thoughts WHEN NEW.id = 'note' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END")
        try { store.restore(fixture(), RestoreMode.REPLACE_ALL); fail("Expected database error") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertData(original, store.read())
    }

    @Test fun invalidSnapshotDoesNotChangeExistingData() = withStore { store, _ ->
        val entry = DayEntry(id = "keep", title = "Оставить")
        store.save(entry)
        val bad = fixture().copy(entries = listOf(entry, entry))
        invalid { store.restore(bad, RestoreMode.REPLACE_ALL) }
        assertEquals(listOf(entry), store.read().entries)
        assertTrue(store.read().thoughts.isEmpty())
    }

    @Test fun sharedContentUriContainsBackupAndGrantsOnlyReadAccess() {
        val uri = BackupFiles.prepareShare(context, BackupCodec.encode(fixture()))
        try {
            assertEquals("content", uri.scheme)
            assertData(fixture(), BackupFiles.read(context, uri).data)
            val intent = BackupFiles.shareIntent(uri)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/json", intent.type)
            assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            invalid { FileProvider.getUriForFile(context, context.packageName + ".backup-files", File(context.filesDir, "private.json")) }
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun savingDocumentTruncatesOldContentsAndProducesReadableBackup() {
        val uri = BackupFiles.prepareShare(context, ByteArray(100_000) { 65 })
        try {
            BackupFiles.save(context, uri, BackupCodec.encode(fixture()))
            assertData(fixture(), BackupFiles.read(context, uri).data)
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
