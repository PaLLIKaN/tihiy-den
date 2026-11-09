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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HolidayUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Holiday element not displayed")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        repeat(8) {
            val current = target ?: throw AssertionError("No clickable holiday action")
            if (current.isClickable) { assertTrue(current.isEnabled); assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            target = current.parent
        }
        throw AssertionError("No clickable holiday ancestor")
    }
    private fun text(value: String) = awaitNode { it.text?.toString() == value }
    private fun scrollTo(value: String): AccessibilityNodeInfo {
        repeat(8) {
            find(automation.rootInActiveWindow) { it.text?.toString() == value }?.let { return it }
            awaitNode { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            automation.waitForIdle(200, 3000)
        }
        return text(value)
    }
    private fun top() {
        repeat(8) {
            val scroll = find(automation.rootInActiveWindow) { it.isScrollable } ?: return
            if (!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            automation.waitForIdle(100, 2000)
        }
    }
    private fun screen(scenario: ActivityScenario<MainActivity>, date: LocalDate, error: String? = null, updates: AtomicInteger = AtomicInteger()) {
        val calendar = instrumentation.targetContext.assets.open("holidays/2026.xml").use { ProductionCalendarCodec.parse(it.readBytes(), 2026) }
        val state = HolidayUiState(2026, true, false, calendar, error = error, holidays = holidaysForCalendar(2026, calendar))
        val entry = DayEntry(id = "fixture-holiday-task", title = "Моя задача", date = date)
        scenario.onActivity { activity ->
            activity.setContent {
                var selected by remember { mutableStateOf(date) }
                var settings by remember { mutableStateOf(HolidaySettings()) }
                var dialog by remember { mutableStateOf(false) }
                DiaryTheme {
                    Surface(Modifier.fillMaxSize().systemBarsPadding().semantics { contentDescription = "Тестовый календарь праздников" }) {
                        CalendarScreen(listOf(entry), selected, { selected = it }, {}, {}, state, settings) { dialog = true }
                    }
                    if (dialog) HolidaySettingsDialog(settings, state, false, { settings = it }, { updates.incrementAndGet() }) { dialog = false }
                }
            }
        }
        awaitNode { it.contentDescription?.toString() == "Тестовый календарь праздников" }
        awaitNode { it.contentDescription?.toString() == "Настроить праздники" }
        automation.waitForIdle(500, 5000)
    }

    @Test fun publicHolidayAndShortenedDayAppearWithoutChangingPersonalTasks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            screen(scenario, LocalDate.of(2026, 11, 4))
            val date = awaitNode { it.contentDescription?.toString()?.startsWith("4 ноября 2026") == true }
            assertTrue("Holiday day label: ${date.contentDescription}", date.contentDescription.toString().contains("День народного единства"))
            scrollTo("День народного единства")
            text("Государственный праздник")
            text("Нерабочий праздничный день")
            scrollTo("Моя задача")
            top()
            click(awaitNode { it.contentDescription?.toString()?.startsWith("3 ноября 2026") == true })
            scrollTo("Сокращённый рабочий день")
        }
    }

    @Test fun professionalFilterHidesOnlyItsCategoryAndKeepsPersonalTask() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            screen(scenario, LocalDate.of(2026, 10, 5))
            scrollTo("День учителя")
            text("Профессиональный праздник")
            text("Рабочий день")
            top()
            click(awaitNode { it.contentDescription?.toString() == "Настроить праздники" })
            click(awaitNode { it.contentDescription?.toString() == "Профессиональные" })
            click(text("Готово"))
            automation.waitForIdle(500, 5000)
            scrollTo("Моя задача")
            assertNull(find(automation.rootInActiveWindow) { it.text?.toString() == "День учителя" })
        }
    }

    @Test fun memorialDatesAndOfflineSettingsRemainAvailableAndRefreshIsExplicit() {
        val updates = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            screen(scenario, LocalDate.of(2026, 4, 12), "Сеть недоступна. Сохранённые праздники доступны.", updates)
            scrollTo("День космонавтики")
            text("Памятная дата")
            automation.waitForIdle(500, 5000)
            automation.takeScreenshot()?.let { bitmap ->
                try { File(instrumentation.targetContext.getExternalFilesDir(null), "holidays-calendar-test.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            top()
            click(awaitNode { it.contentDescription?.toString() == "Настроить праздники" })
            text("Праздники России")
            scrollTo("Обновить сейчас")
            text("Сеть недоступна. Сохранённые праздники доступны.")
            click(text("Обновить сейчас"))
            assertEquals(1, updates.get())
            click(text("Готово"))
        }
    }
}
