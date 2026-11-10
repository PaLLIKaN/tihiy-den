package ru.tihiyden.diary

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedCoverUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun await(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Shared cover element not displayed: $label")
    }

    private fun text(value: String) = await(value) { it.text?.toString() == value }
    private fun description(value: String) = await(value) { it.contentDescription?.toString() == value }

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

    @Test fun browserPageShareResolvesToDiaryAndOffersGallery() {
        val context = instrumentation.targetContext
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
            .putExtra(Intent.EXTRA_TEXT, "Страница в браузере\nhttps://example.com/")
        assertEquals(MainActivity::class.java.name, share.resolveActivity(context.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(Intent(share).setClass(context, MainActivity::class.java)).use {
            text("Ссылка из браузера")
            text("Открыть в браузере")
            text("Выбрать из галереи")
            click(text("Отмена"))
            text("Календарь")
        }
    }

    @Test fun browserImageLinkLoadsTheImageAfterConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNetwork") == "true")
        val context = instrumentation.targetContext
        val image = "https://raw.githubusercontent.com/github/explore/main/topics/kotlin/kotlin.png"
        val page = Uri.parse("https://yandex.ru/images/search").buildUpon().appendQueryParameter("img_url", image).build()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setClass(context, MainActivity::class.java)
            .putExtra(Intent.EXTRA_TEXT, page.toString())
        ActivityScenario.launch<MainActivity>(share).use {
            text("Ссылка из браузера")
            text("raw.githubusercontent.com")
            click(text("Загрузить"))
            text("Обложка из другого приложения")
            description("Обложка")
            click(text("Отмена"))
        }
    }

    @Test fun shareOffersPreviewAndKeepsAnUnsavedEditorOnWarmLaunch() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "cover-test-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        try {
            val bitmap = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val share = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(context.packageName).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(resolver, "Тестовая обложка", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertEquals(MainActivity::class.java.name, share.resolveActivity(context.packageManager)?.className)
            ActivityScenario.launch<MainActivity>(Intent(share).setClass(context, MainActivity::class.java)).use {
                text("Обложка из другого приложения")
                text("Новая запись")
                description("Обложка")
                click(text("Отмена"))
                text("Календарь")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click(description("Открыть меню"))
                click(text("Развлечения"))
                click(description("Добавить"))
                text("В коллекцию")
                click(text("Сериалы"))
                val title = "Черновик сериала"
                val edit = await("title input") { it.isEditable }
                assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
                }))
                click(text("Подобрать обложку"))
                text("Искать в браузере")

                // Reenter the activity as a browser would after the user shares an image.
                scenario.onActivity { it.startActivity(Intent(share).setClass(context, MainActivity::class.java)) }
                text("Обложка из другого приложения")
                text("Картинка будет добавлена в открытый редактор.")
                description("Обложка")
                click(text("Использовать"))
                text(title)
                description("Обложка")
                text("Заменить")
                text("Сохранить")
                click(description("Закрыть"))
                text("Закрыть без сохранения?")
                click(text("Закрыть"))
            }
        } finally { resolver.delete(uri, null, null) }
    }
}
