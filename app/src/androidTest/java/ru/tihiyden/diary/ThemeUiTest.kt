package ru.tihiyden.diary

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Theme element not displayed")
    }
    private fun text(value: String) = awaitNode { it.text?.toString() == value }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) { if (current?.isClickable == true) return current; current = current?.parent }
        return null
    }
    private fun action(value: String) = awaitNode { it.text?.toString() == value && clickable(it)?.isEnabled == true }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        repeat(8) {
            val current = target ?: throw AssertionError("No clickable theme action")
            if (current.isClickable) { assertTrue(current.isEnabled); assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            target = current.parent
        }
        throw AssertionError("No clickable theme ancestor")
    }
    private fun openPicker() {
        click(awaitNode { it.contentDescription?.toString() == "Открыть меню" })
        val theme = text("Цветовая гамма")
        val calendar = text("Календарь")
        val first = Rect().also(theme::getBoundsInScreen)
        val second = Rect().also(calendar::getBoundsInScreen)
        assertTrue(first.top < second.top)
        click(theme)
        awaitNode { it.contentDescription?.toString() == "Цветовой круг" }
    }
    private fun field() = awaitNode { it.className?.toString() == "android.widget.EditText" }
    private fun setHex(value: String) {
        assertTrue(field().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        awaitNode { it.className?.toString() == "android.widget.EditText" && it.text?.toString()?.contains(value) == true }
    }
    private fun scrollTo(value: String): AccessibilityNodeInfo {
        repeat(6) {
            find(automation.rootInActiveWindow) { it.text?.toString() == value }?.let { return it }
            val scroll = awaitNode { it.isScrollable }
            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            automation.waitForIdle(200, 3000)
        }
        return text(value)
    }
    private fun awaitColor(scenario: ActivityScenario<MainActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var actual = 0
            var saving = true
            scenario.onActivity {
                val theme = ViewModelProvider(it)[ThemeViewModel::class.java]
                actual = theme.accent.value
                saving = theme.saving.value
            }
            if (!saving && actual == expected && ThemePreferences(context).read() == expected) return
            SystemClock.sleep(100)
        }
        fail("Theme was not saved")
    }

    @Test fun wheelChangesColorAndAppliedThemeSurvivesActivityRecreation() {
        val original = ThemePreferences(context).read()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openPicker()
                automation.waitForIdle(500, 5000)
                val wheel = awaitNode { it.contentDescription?.toString() == "Цветовой круг" }
                val bounds = Rect().also(wheel::getBoundsInScreen)
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY() - bounds.height() * .3f
                val time = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).let { event ->
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                }
                awaitNode { it.contentDescription?.toString() == "Цветовой круг" && it.stateDescription?.toString()?.contains("270") == true }
                val slider = awaitNode { it.contentDescription?.toString() == "Яркость цвета" }
                assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .7f) }))
                text("Яркость · 70%")
                setHex("426697")
                automation.waitForIdle(500, 5000)
                automation.takeScreenshot()?.let { bitmap ->
                    try { File(context.getExternalFilesDir(null), "theme-picker-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
                click(text("Применить"))
                awaitColor(scenario, 0xff426697.toInt())
                scenario.recreate()
                awaitColor(scenario, 0xff426697.toInt())
                text("Календарь")
                automation.waitForIdle(500, 5000)
                automation.takeScreenshot()?.let { bitmap ->
                    try { File(context.getExternalFilesDir(null), "theme-calendar-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
        } finally { ThemePreferences(context).save(original) }
    }

    @Test fun cancellingDraftKeepsOriginalAndResetRestoresDefault() {
        val original = ThemePreferences(context).read()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openPicker()
                setHex("A55470")
                click(text("Отмена"))
                assertEquals(original, ThemePreferences(context).read())
                click(action("Цветовая гамма"))
                setHex("A55470")
                click(scrollTo("Вернуть исходный цвет"))
                automation.waitForIdle(500, 5000)
                click(text("Применить"))
                awaitColor(scenario, DEFAULT_ACCENT)
            }
        } finally { ThemePreferences(context).save(original) }
    }

    @Test fun unsavedColorDraftSurvivesRotationWithoutChangingSavedTheme() {
        val original = ThemePreferences(context).read()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openPicker()
            setHex("795B91")
            scenario.recreate()
            awaitNode { it.contentDescription?.toString() == "Цветовой круг" }
            assertTrue(field().text.toString().contains("795B91"))
            assertEquals(original, ThemePreferences(context).read())
            click(text("Отмена"))
        }
    }
}
