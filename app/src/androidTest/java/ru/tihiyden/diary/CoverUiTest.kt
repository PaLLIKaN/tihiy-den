package ru.tihiyden.diary

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CoverUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Cover editor element not displayed")
    }
    private fun text(value: String) = awaitNode { it.text?.toString() == value }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) { if (current?.isClickable == true) return current; current = current?.parent }
        return null
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        repeat(8) {
            val current = target ?: throw AssertionError("No clickable ancestor")
            if (current.isClickable) { assertTrue("Button disabled: ${node.text}", current.isEnabled); assertTrue("Click rejected: ${node.text}", current.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            target = current.parent
        }
        throw AssertionError("No clickable cover action")
    }
    private class Picker(var selected: Uri?) : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            dispatchResult<Uri?>(requestCode, selected)
        }
    }
    private fun editor(scenario: ActivityScenario<MainActivity>, media: MediaEntry, picker: Picker, saved: AtomicReference<MediaEntry?>) {
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    DiaryTheme { MediaEditor(media, false, {}, { saved.set(it) }, null) }
                }
            }
        }
        text("Обложка")
    }
    private fun saveResult(saved: AtomicReference<MediaEntry?>): MediaEntry {
        automation.waitForIdle(500, 5000)
        val save = awaitNode { it.text?.toString() == "Сохранить" && clickable(it)?.isEnabled == true }
        click(save)
        instrumentation.waitForIdleSync()
        return requireNotNull(saved.get())
    }

    @Test fun eachMediaKindCanSelectAndRemoveCoverWithoutChangingText() {
        val context = instrumentation.targetContext
        val uri = BackupFiles.prepareShare(context, Base64.getDecoder().decode(sampleCover()))
        try {
            for (kind in MediaKind.entries) {
                val media = MediaEntry(title = "История", kind = kind, thoughts = "Мои впечатления")
                val saved = AtomicReference<MediaEntry?>(null)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    editor(scenario, media, Picker(uri), saved)
                    click(text("Добавить"))
                    text("Убрать обложку")
                    if (kind == MediaKind.BOOK) {
                        automation.waitForIdle(500, 5000)
                        automation.takeScreenshot()?.let { bitmap ->
                            try { File(context.getExternalFilesDir(null), "cover-editor-test.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            finally { bitmap.recycle() }
                        }
                    }
                    val selected = saveResult(saved)
                    assertTrue(selected.cover.isNotEmpty())
                    CoverImages.validate(selected.cover)
                    assertEquals(media.kind, selected.kind)
                    assertEquals(media.title, selected.title)
                    assertEquals(media.thoughts, selected.thoughts)
                    click(text("Убрать обложку"))
                    text("Добавить")
                    assertEquals("", saveResult(saved).cover)
                }
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun cancelledAndInvalidSelectionsKeepThePreviousCover() {
        val context = instrumentation.targetContext
        val bad = BackupFiles.prepareShare(context, "invalid image".toByteArray())
        try {
            val media = MediaEntry(title = "Не потерять", cover = sampleCover())
            val saved = AtomicReference<MediaEntry?>(null)
            val picker = Picker(null)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                editor(scenario, media, picker, saved)
                click(text("Заменить"))
                assertEquals(media.cover, saveResult(saved).cover)
                picker.selected = bad
                click(text("Заменить"))
                text("Не удалось открыть картинку. Попробуйте другое изображение.")
                click(text("Понятно"))
                assertEquals(media.cover, saveResult(saved).cover)
            }
        } finally { context.contentResolver.delete(bad, null, null) }
    }

    @Test fun actualSystemImagePickerOpensAndCanBeCancelled() {
        ActivityScenario.launch(MainActivity::class.java).use {
            click(awaitNode { it.contentDescription?.toString() == "Открыть меню" })
            click(text("Развлечения"))
            click(awaitNode { it.contentDescription?.toString() == "Добавить" })
            text("Обложка")
            click(text("Добавить"))
            awaitNode { node -> node.packageName?.toString() in listOf("com.google.android.providers.media.module", "com.android.providers.media.module", "com.google.android.documentsui", "com.android.documentsui") }
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            text("Обложка")
            text("Добавить")
        }
    }
}
