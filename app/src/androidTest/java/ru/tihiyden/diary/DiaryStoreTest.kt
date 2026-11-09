package ru.tihiyden.diary

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DiaryStoreTest {
    @Test fun dataSurvivesReopenAndEditingDoesNotDuplicateRows() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String) = super.getDatabasePath("test_$name")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
                super.openOrCreateDatabase("test_$name", mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) =
                super.openOrCreateDatabase("test_$name", mode, factory, errorHandler)
        }
        target.deleteDatabase("test_diary.db")
        try {
            val entry = DayEntry(title = "Прогулка", date = LocalDate.of(2026, 10, 26))
            val thought = Thought(text = "Тихое утро", createdAt = 1234L)
            val book = MediaEntry(title = "Книга", thoughts = "Запомнить эту мысль")
            DiaryStore(context).use { store ->
                store.save(entry)
                store.save(thought)
                store.save(book)
                store.save(entry.copy(done = true))
                store.save(book.copy(status = MediaStatus.DONE))
            }
            DiaryStore(context).use { store ->
                val data = store.read()
                assertEquals(1, data.entries.size)
                assertTrue(data.entries.single().done)
                assertEquals(thought, data.thoughts.single())
                assertEquals(MediaStatus.DONE, data.media.single().status)
                store.deleteEntry(entry.id)
                assertTrue(store.read().entries.isEmpty())
                assertEquals(1, store.read().thoughts.size)
                assertEquals(1, store.read().media.size)
            }
        } finally { target.deleteDatabase("test_diary.db") }
    }
}
