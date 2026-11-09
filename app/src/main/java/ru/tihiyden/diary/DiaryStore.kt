package ru.tihiyden.diary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/** A private on-device database. Each user action updates one row atomically. */
class DiaryStore(context: Context, databaseName: String = "diary.db") : SQLiteOpenHelper(context, databaseName, null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE day_entries (id TEXT PRIMARY KEY, title TEXT NOT NULL, details TEXT NOT NULL, day TEXT NOT NULL, time TEXT NOT NULL, kind TEXT NOT NULL, done INTEGER NOT NULL, priority TEXT NOT NULL DEFAULT 'NORMAL', due_day TEXT)")
        db.execSQL("CREATE INDEX entries_day ON day_entries(day)")
        db.execSQL("CREATE TABLE thoughts (id TEXT PRIMARY KEY, text TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE media (id TEXT PRIMARY KEY, title TEXT NOT NULL, creator TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, thoughts TEXT NOT NULL, updated_at INTEGER NOT NULL, cover TEXT NOT NULL DEFAULT '')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE media ADD COLUMN cover TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE day_entries ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
            db.execSQL("ALTER TABLE day_entries ADD COLUMN due_day TEXT")
        }
    }

    fun read(): DiaryData {
        val db = readableDatabase
        val entries = buildList {
            db.rawQuery("SELECT id,title,details,day,time,kind,done,priority,due_day FROM day_entries", null).use { c ->
                while (c.moveToNext()) add(DayEntry(c.getString(0), c.getString(1), c.getString(2), LocalDate.parse(c.getString(3)), c.getString(4), EntryKind.valueOf(c.getString(5)), c.getInt(6) == 1,
                    TaskPriority.valueOf(c.getString(7)), if (c.isNull(8)) null else LocalDate.parse(c.getString(8))))
            }
        }
        val thoughts = buildList {
            db.rawQuery("SELECT id,text,created_at,updated_at FROM thoughts ORDER BY created_at DESC", null).use { c ->
                while (c.moveToNext()) add(Thought(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)))
            }
        }
        val media = buildList {
            db.rawQuery("SELECT id,title,creator,kind,status,thoughts,updated_at,cover FROM media ORDER BY updated_at DESC", null).use { c ->
                while (c.moveToNext()) add(MediaEntry(c.getString(0), c.getString(1), c.getString(2), MediaKind.valueOf(c.getString(3)), MediaStatus.valueOf(c.getString(4)), c.getString(5), c.getLong(6), c.getString(7)))
            }
        }
        return DiaryData(entries, thoughts, media)
    }

    fun save(entry: DayEntry) {
        entry.validateSchedule()
        upsert("day_entries", ContentValues().apply {
            put("id", entry.id); put("title", entry.title); put("details", entry.details)
            put("day", entry.date.toString()); put("time", entry.time)
            put("kind", entry.kind.name); put("done", if (entry.done) 1 else 0)
            put("priority", entry.priority.name); put("due_day", entry.dueDate?.toString())
        })
    }

    fun save(thought: Thought) = upsert("thoughts", ContentValues().apply {
        put("id", thought.id); put("text", thought.text)
        put("created_at", thought.createdAt); put("updated_at", thought.updatedAt)
    })

    fun save(media: MediaEntry) = upsert("media", ContentValues().apply {
        put("id", media.id); put("title", media.title); put("creator", media.creator)
        put("kind", media.kind.name); put("status", media.status.name)
        put("thoughts", media.thoughts); put("updated_at", media.updatedAt)
        put("cover", media.cover)
    })

    private fun upsert(table: String, values: ContentValues) {
        writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
            check(it != -1L) { "Не удалось сохранить запись" }
        }
    }

    fun deleteEntry(id: String) { writableDatabase.delete("day_entries", "id = ?", arrayOf(id)) }
    fun toggleEntry(id: String) { writableDatabase.execSQL("UPDATE day_entries SET done = 1 - done WHERE id = ? AND kind = ?", arrayOf(id, EntryKind.TASK.name)) }
    fun deleteThought(id: String) { writableDatabase.delete("thoughts", "id = ?", arrayOf(id)) }
    fun deleteMedia(id: String) { writableDatabase.delete("media", "id = ?", arrayOf(id)) }

    /** All three tables are restored together, or none of them are changed. */
    fun restore(data: DiaryData, mode: RestoreMode): RestoreResult {
        BackupCodec.validate(data)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = if (mode == RestoreMode.MERGE_MISSING) read() else DiaryData()
            val entryIds = existing.entries.map { it.id }.toHashSet()
            val thoughtIds = existing.thoughts.map { it.id }.toHashSet()
            val mediaIds = existing.media.map { it.id }.toHashSet()
            if (mode == RestoreMode.REPLACE_ALL) {
                db.delete("day_entries", null, null)
                db.delete("thoughts", null, null)
                db.delete("media", null, null)
            }
            var count = 0
            data.entries.filterNot { it.id in entryIds }.forEach { save(it); count++ }
            data.thoughts.filterNot { it.id in thoughtIds }.forEach { save(it); count++ }
            data.media.filterNot { it.id in mediaIds }.forEach { save(it); count++ }
            val restored = read()
            db.setTransactionSuccessful()
            return RestoreResult(count, restored)
        } finally { db.endTransaction() }
    }
}
