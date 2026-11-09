package ru.tihiyden.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.database.sqlite.SQLiteDatabase
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

fun sampleCover(color: Int = Color.rgb(65, 96, 74)): String {
    val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.rgb(237, 226, 192); textSize = 36f }
        drawCircle(200f, 250f, 90f, paint)
        drawText("ТИХИЙ", 125f, 440f, paint)
        drawText("ДЕНЬ", 145f, 485f, paint)
    }
    return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray().let { Base64.getEncoder().encodeToString(it) } }
    finally { bitmap.recycle() }
}

@RunWith(AndroidJUnit4::class)
class CoverTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid image to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun largePhotoIsBoundedAndIndependentOfSource() {
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(42)
        bitmap.setPixels(IntArray(1200 * 1800) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }, 0, 1200, 0, 0, 1200, 1800)
        val source = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val uri = BackupFiles.prepareShare(context, source)
        var reference: String? = null
        try {
            reference = CoverImages.importDraft(context, uri)
            context.contentResolver.delete(uri, null, null)
            val cover = CoverImages.readDraft(context, reference)
            assertTrue(Base64.getDecoder().decode(cover).size <= CoverImages.MAX_BYTES)
            CoverImages.validate(cover)
            val decoded = CoverImages.decode(cover, 800)!!
            assertTrue(decoded.width <= 800 && decoded.height <= 800)
            assertEquals(2f / 3f, decoded.width.toFloat() / decoded.height, .01f)
            decoded.recycle()
        } finally {
            context.contentResolver.delete(uri, null, null)
            reference?.let { File(File(context.cacheDir, "cover-drafts"), it).delete() }
        }
    }

    @Test fun imageOrientationIsAppliedBeforeSaving() {
        val source = File.createTempFile("orientation-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val uri = BackupFiles.prepareShare(context, source.readBytes())
        var reference: String? = null
        try {
            reference = CoverImages.importDraft(context, uri)
            val decoded = CoverImages.decode(CoverImages.readDraft(context, reference), 800)!!
            assertEquals(240, decoded.width)
            assertEquals(160, decoded.height)
            decoded.recycle()
        } finally {
            source.delete(); context.contentResolver.delete(uri, null, null)
            reference?.let { File(File(context.cacheDir, "cover-drafts"), it).delete() }
        }
    }

    @Test fun coversForAllKindsSurviveBackupDatabaseReopenReplacementAndRemoval() {
        val name = "covers_${UUID.randomUUID()}.db"
        val cover = sampleCover()
        val original = DiaryData(media = MediaKind.entries.map { MediaEntry(title = it.label, kind = it, cover = cover) })
        try {
            DiaryStore(context, name).use { it.restore(BackupCodec.decode(BackupCodec.encode(original)).data, RestoreMode.REPLACE_ALL) }
            DiaryStore(context, name).use { store ->
                assertEquals(original.media.toSet(), store.read().media.toSet())
                val first = original.media.first()
                val changed = first.copy(cover = sampleCover(Color.BLUE))
                store.save(changed)
                assertEquals(changed, store.read().media.single { it.id == first.id })
                store.save(changed.copy(cover = ""))
                assertEquals("", store.read().media.single { it.id == first.id }.cover)
                assertEquals(3, store.read().media.size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun legacyBackupAndDatabaseMigrateWithoutLosingExistingFields() {
        val original = DiaryData(
            entries = listOf(DayEntry(id = "task", title = "Важное дело", done = true)),
            thoughts = listOf(Thought(id = "note", text = "Моя мысль", createdAt = 123, updatedAt = 456)),
            media = listOf(MediaEntry(id = "book", title = "Моя книга", creator = "Автор", status = MediaStatus.DONE, thoughts = "Впечатления", updatedAt = 789)),
        )
        for (version in 1..2) {
            val book = if (version == 2) original.media.single().copy(cover = sampleCover()) else original.media.single()
            val legacy = original.copy(media = listOf(book))
            val json = JSONObject(BackupCodec.encode(legacy).toString(Charsets.UTF_8)).put("version", version)
            json.getJSONArray("entries").getJSONObject(0).apply { remove("priority"); remove("dueDate") }
            if (version == 1) json.getJSONArray("media").getJSONObject(0).remove("cover")
            assertEquals(legacy, BackupCodec.decode(json.toString().toByteArray()).data)
            val name = "legacy_${UUID.randomUUID()}.db"
            try {
                context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE day_entries (id TEXT PRIMARY KEY, title TEXT NOT NULL, details TEXT NOT NULL, day TEXT NOT NULL, time TEXT NOT NULL, kind TEXT NOT NULL, done INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE thoughts (id TEXT PRIMARY KEY, text TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE media (id TEXT PRIMARY KEY, title TEXT NOT NULL, creator TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, thoughts TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO day_entries VALUES ('task','Важное дело','',?,'','TASK',1)", arrayOf(original.entries.single().date.toString()))
                db.execSQL("INSERT INTO thoughts VALUES ('note','Моя мысль',123,456)")
                db.execSQL("INSERT INTO media VALUES ('book','Моя книга','Автор','BOOK','DONE','Впечатления',789)")
                    if (version == 2) {
                        db.execSQL("ALTER TABLE media ADD COLUMN cover TEXT NOT NULL DEFAULT ''")
                        db.execSQL("UPDATE media SET cover = ? WHERE id = 'book'", arrayOf(book.cover))
                    }
                    db.version = version
                }
                DiaryStore(context, name).use { store ->
                    assertEquals(legacy, store.read())
                    assertEquals(3, store.readableDatabase.version)
                    val updated = legacy.entries.single().copy(priority = TaskPriority.HIGH, dueDate = legacy.entries.single().date.plusDays(2))
                    store.save(updated)
                    assertEquals(legacy.copy(entries = listOf(updated)), store.read())
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun invalidCoversAreRejectedBeforeAnyDataIsReplaced() {
        val name = "bad_cover_${UUID.randomUUID()}.db"
        try {
            DiaryStore(context, name).use { store ->
                val keep = MediaEntry(title = "Сохранить", cover = sampleCover())
                store.save(keep)
                for (bad in listOf("not an image", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), "A".repeat(CoverImages.MAX_BYTES * 2))) {
                    invalid { store.restore(DiaryData(media = listOf(MediaEntry(title = "Плохая копия", cover = bad))), RestoreMode.REPLACE_ALL) }
                    assertEquals(listOf(keep), store.read().media)
                }
                val json = JSONObject(BackupCodec.encode(DiaryData(media = listOf(keep))).toString(Charsets.UTF_8))
                json.getJSONArray("media").getJSONObject(0).put("cover", 123)
                invalid { BackupCodec.decode(json.toString().toByteArray()) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun corruptSelectionDoesNotCreateDraftAndDraftReferencesCannotEscapeCache() {
        val uri = BackupFiles.prepareShare(context, "not an image".toByteArray())
        try {
            try { CoverImages.importDraft(context, uri); fail("Expected invalid selection") } catch (_: java.io.IOException) { } catch (_: IllegalArgumentException) { }
            invalid { CoverImages.readDraft(context, "../../private.json") }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
