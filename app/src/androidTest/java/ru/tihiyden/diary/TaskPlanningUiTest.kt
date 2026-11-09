package ru.tihiyden.diary

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskPlanningUiTest {
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

    private fun await(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Task planning element not displayed: $label")
    }

    private fun idle() = automation.waitForIdle(250, 5_000)

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                idle()
                return
            }
            current = current.parent
        }
        throw AssertionError("Could not click ${node.text ?: node.contentDescription}")
    }

    private fun text(value: String) = await(value) { it.text?.toString() == value }

    private fun scrollTo(value: String): AccessibilityNodeInfo {
        repeat(8) {
            find { it.text?.toString() == value }?.let { return it }
            if (find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return text(value)
            idle()
        }
        return text(value)
    }

    private fun selectDay(day: Int) {
        repeat(8) {
            if (find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return@repeat
            idle()
        }
        click(await("$day ноября") { it.contentDescription?.toString() == "$day ноября 2028" })
    }

    private fun screenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), "$name-test.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun deadlinePriorityAndFiltersWorkAcrossCalendarDaysWithOneStoredTask() {
        val context = instrumentation.targetContext
        val name = "task_planning_${UUID.randomUUID()}.db"
        val start = LocalDate.of(2028, 11, 12)
        val task = DayEntry(title = "Подготовить доклад", date = start)
        val normal = DayEntry(title = "Забрать посылку", date = start.plusDays(2))
        val event = DayEntry(title = "Встреча с друзьями", date = start.plusDays(2), kind = EntryKind.EVENT)
        try {
            DiaryStore(context, name).use { store ->
                listOf(task, normal, event).forEach(store::save)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            var day by remember { mutableStateOf(start) }
                            var entries by remember { mutableStateOf(store.read().entries) }
                            var edit by remember { mutableStateOf<DayEntry?>(null) }
                            DiaryTheme {
                                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                                    CalendarScreen(entries, day, { day = it }, { edit = it }, {
                                        store.toggleEntry(it.id)
                                        entries = store.read().entries
                                    }, holidaySettings = HolidaySettings(enabled = false))
                                }
                                edit?.let { current ->
                                    EntryEditor(current, false, { edit = null }, {
                                        store.save(it)
                                        entries = store.read().entries
                                        edit = null
                                    }, null)
                                }
                            }
                        }
                    }
                    text("12 ноября")
                    click(scrollTo(task.title))
                    click(text("Указать срок выполнения"))
                    click(await("дата 15 в выборе срока") { it.text?.toString() == "15" && it.isEnabled })
                    click(await("подтверждение срока") { it.viewIdResourceName == "android:id/button1" })
                    click(scrollTo("Высокий"))
                    scrollTo("Срок: 15 ноября 2028")
                    screenshot("task-editor")
                    click(text("Сохранить"))

                    selectDay(14)
                    scrollTo(task.title)
                    text("Срок: 15 ноября")
                    scrollTo(normal.title)
                    selectDay(14)
                    click(scrollTo("Фильтры задач"))
                    click(text("В работе"))
                    click(text("Высокий"))
                    screenshot("task-filters")
                    click(text("Применить"))
                    scrollTo(task.title)
                    scrollTo(event.title)
                    assertNull(find { it.text?.toString() == normal.title })
                    screenshot("task-calendar")
                    selectDay(14)
                    scrollTo(task.title)
                    click(await("отметка о выполнении") { it.contentDescription?.toString() == "Выполнено: ${task.title}" })
                    scrollTo("Нет задач по этим фильтрам")

                    selectDay(15)
                    click(scrollTo("Фильтры задач · 2"))
                    click(text("Выполнены"))
                    click(text("Применить"))
                    scrollTo(task.title)
                    assertTrue(await("выполненная задача") { it.contentDescription?.toString() == "Выполнено: ${task.title}" }.isChecked)
                    selectDay(16)
                    scrollTo("День пока свободен")
                    val saved = store.read().entries
                    assertEquals(3, saved.size)
                    assertEquals(task.copy(done = true, priority = TaskPriority.HIGH, dueDate = start.plusDays(3)), saved.single { it.id == task.id })
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
