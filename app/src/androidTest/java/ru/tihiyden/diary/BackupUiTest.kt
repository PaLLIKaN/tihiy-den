package ru.tihiyden.diary

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Exercise the actual Android document picker and share sheet without uploading user data. */
@RunWith(AndroidJUnit4::class)
class BackupUiTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            find(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected Android accessibility element was not displayed")
    }

    private fun text(value: String) = awaitNode { it.text?.toString() == value }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        repeat(8) {
            val current = target ?: throw AssertionError("No clickable ancestor")
            if (current.isClickable) {
                assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            target = current.parent
        }
        throw AssertionError("Element was not clickable")
    }

    private fun openBackup() {
        click(awaitNode { it.contentDescription?.toString() == "Открыть меню" })
        click(text("Резервная копия"))
        text("Сохранить файл")
    }

    private fun backToBackup() {
        repeat(3) {
            if (find(automation.rootInActiveWindow) { it.text?.toString() == "Сохранить файл" } != null) return
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            SystemClock.sleep(500)
        }
        text("Сохранить файл")
    }

    @Test fun calendarShowsTodaysQuoteAndBackupOffersAllActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            text("«${quoteForDay(LocalDate.now())}»")
            awaitNode { it.text?.toString()?.startsWith("Цитата дня ·") == true }
            openBackup()
            text("Отправить копию")
            text("Восстановить из файла")
        }
    }

    @Test fun documentExportOpensSystemPickerAndCancellationReturnsToApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openBackup()
            click(text("Сохранить файл"))
            awaitNode { it.text?.toString()?.contains("tihiy-den-backup-") == true }
            backToBackup()
        }
    }

    @Test fun shareExportOpensAndroidChooserWithoutSendingToAnyApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openBackup()
            click(text("Отправить копию"))
            awaitNode { node ->
                val pkg = node.packageName?.toString().orEmpty()
                pkg == "com.android.intentresolver" || (pkg == "android" && node.text?.toString() == "Куда отправить копию?")
            }
            backToBackup()
            text("Отправить копию")
        }
    }

    @Test fun restorePreviewRequiresConfirmationAndDefaultsToKeepingCurrentData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = BackupFiles.prepareShare(context, BackupCodec.encode(DiaryData()))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openBackup()
                scenario.onActivity { activity -> ViewModelProvider(activity)[DiaryViewModel::class.java].inspectBackup(uri) }
                text("Восстановление копии")
                text("Добавить недостающие")
                text("Добавить записи")
                click(text("Отмена"))
                text("Сохранить файл")
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
