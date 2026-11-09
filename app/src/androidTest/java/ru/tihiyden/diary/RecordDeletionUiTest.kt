package ru.tihiyden.diary

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordDeletionUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(automation.rootInActiveWindow)
    }

    private fun awaitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Deletion element not displayed: $label")
    }

    private fun text(value: String) = awaitNode(value) { it.text?.toString() == value }

    private fun visibleCard(title: String): AccessibilityNodeInfo {
        repeat(7) {
            awaitNode("$title or records list") { it.text?.toString() == title || it.isScrollable }
            val card = find { it.text?.toString() == title }
            if (card != null) {
                val bounds = Rect().also(card::getBoundsInScreen)
                val display = instrumentation.targetContext.resources.displayMetrics
                if (bounds.height() >= 16 * display.density && bounds.centerY() < display.heightPixels - 100 * display.density) return card
            }
            val scrollable = find { it.isScrollable } ?: return card ?: text(title)
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return text(title)
            automation.waitForIdle(250, 5_000)
        }
        return text(title)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                automation.waitForIdle(200, 5_000)
                return
            }
            current = current.parent
        }
        error("No click action for ${node.text ?: node.contentDescription}")
    }

    private fun hold(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val x = bounds.left + bounds.width() / 4
        val y = bounds.centerY()
        val duration = ViewConfiguration.getLongPressTimeout() + 500
        val output = automation.executeShellCommand("input swipe $x $y $x $y $duration")
        ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { stream ->
            assertTrue(stream.readText().isBlank())
        }
    }

    private fun navigate(section: Section) {
        click(awaitNode("navigation menu") { it.contentDescription?.toString() == "Открыть меню" })
        click(text(section.title))
        text(section.subtitle)
    }

    private fun delete(title: String, question: String) {
        hold(visibleCard(title))
        text(question)
        text(title)
        click(text("Удалить"))
    }

    @Test fun longPressDeletesEveryRecordTypeOnlyAfterConfirmation() {
        val context = instrumentation.targetContext
        val databaseName = "record-deletion-${UUID.randomUUID()}.db"
        val today = LocalDate.now()
        val task = DayEntry(title = "Проверка долгого нажатия", date = today.minusDays(1), dueDate = today.plusDays(1))
        val event = DayEntry(title = "Тестовое событие", date = today, kind = EntryKind.EVENT)
        val thought = Thought(text = "Тестовая мысль")
        val keep = Thought(text = "Другая мысль должна остаться", createdAt = 1)
        val media = listOf(
            MediaEntry(title = "Тестовая книга", kind = MediaKind.BOOK),
            MediaEntry(title = "Тестовый фильм", kind = MediaKind.MOVIE),
            MediaEntry(title = "Тестовый сериал", kind = MediaKind.SERIES),
        )
        try {
            DiaryStore(context, databaseName).use { store ->
                store.save(task)
                store.save(event)
                store.save(thought)
                store.save(keep)
                media.forEach(store::save)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var vm: DiaryViewModel
                    scenario.onActivity { activity ->
                        vm = DiaryViewModel(activity.application, store)
                        activity.setContent { DiaryTheme { DiaryApp(vm) } }
                    }
                    try {
                        hold(visibleCard(task.title))
                        text("Удалить задачу?")
                        text("Задача будет удалена со всех дат.")
                        click(text("Отмена"))
                        assertTrue(store.read().entries.any { it.id == task.id })
                        delete(task.title, "Удалить задачу?")
                        delete(event.title, "Удалить событие?")

                        navigate(Section.THOUGHTS)
                        click(visibleCard(thought.text))
                        text("Ваша мысль")
                        click(awaitNode("close editor") { it.contentDescription?.toString() == "Закрыть" })
                        delete(thought.text, "Удалить мысль?")

                        navigate(Section.MEDIA)
                        delete(media[0].title, "Удалить книгу?")
                        click(text("Фильмы"))
                        delete(media[1].title, "Удалить фильм?")
                        click(text("Сериалы"))
                        delete(media[2].title, "Удалить сериал?")
                        text("Ваша коллекция историй")
                        assertEquals(DiaryData(thoughts = listOf(keep)), store.read())
                    } finally { vm.viewModelScope.cancel() }
                }
            }
        } finally { context.deleteDatabase(databaseName) }
    }
}
