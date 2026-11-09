package ru.tihiyden.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class CoverLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val edges = listOf(0xffd34a4a.toInt(), 0xff4a9960.toInt(), 0xff436dc4.toInt(), 0xffe3ba48.toInt())

    private fun image(width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.DKGRAY)
            val paint = Paint()
            paint.color = edges[0]; drawRect(0f, 0f, width * .1f, height.toFloat(), paint)
            paint.color = edges[1]; drawRect(width * .9f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = edges[2]; drawRect(0f, 0f, width.toFloat(), height * .1f, paint)
            paint.color = edges[3]; drawRect(0f, height * .9f, width.toFloat(), height.toFloat(), paint)
        }
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().let { Base64.getEncoder().encodeToString(it) } }
        finally { bitmap.recycle() }
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun displayedCover(ratio: Float): Rect {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = find(automation.rootInActiveWindow) { it.contentDescription?.toString() == "Обложка" }
            if (node != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.height() > 0 && kotlin.math.abs(bounds.width().toFloat() / bounds.height() - ratio) < .025f) return bounds
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Cover did not retain aspect ratio $ratio")
    }

    private fun assertAllEdges(bounds: Rect, screenshotName: String? = null) {
        automation.waitForIdle(500, 5000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            val points = listOf(
                bounds.left + (bounds.width() * .04f).toInt() to bounds.centerY(),
                bounds.right - (bounds.width() * .04f).toInt() to bounds.centerY(),
                bounds.centerX() to bounds.top + (bounds.height() * .04f).toInt(),
                bounds.centerX() to bounds.bottom - (bounds.height() * .04f).toInt(),
            )
            points.zip(edges).forEach { (point, expected) ->
                val actual = bitmap.getPixel(point.first, point.second)
                assertTrue("An edge was cropped or letterboxed", kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 35 && kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 35 && kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 35)
            }
            if (screenshotName != null) File(instrumentation.targetContext.getExternalFilesDir(null), screenshotName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun landscapePortraitAndSquareArtworkKeepsAllEdgesInCollectionAndEditor() {
        for (kind in MediaKind.entries) for ((width, height) in listOf(800 to 450, 400 to 600, 500 to 500)) {
            val media = MediaEntry(title = "История", kind = kind, cover = image(width, height), thoughts = "Картинка видна целиком")
            var showEditor: (() -> Unit)? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        var edit by remember { mutableStateOf(false) }
                        showEditor = { edit = true }
                        DiaryTheme {
                            Surface(Modifier.fillMaxSize().systemBarsPadding()) { MediaScreen(listOf(media), kind, {}, { edit = true }) }
                            if (edit) MediaEditor(media, false, { edit = false }, {}, null)
                        }
                    }
                }
                val ratio = width.toFloat() / height
                val screenshot = kind == MediaKind.SERIES && width > height
                assertAllEdges(displayedCover(ratio), if (screenshot) "landscape-collection-test.png" else null)
                scenario.onActivity { requireNotNull(showEditor).invoke() }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (find(automation.rootInActiveWindow) { it.text?.toString() == "В коллекцию" } == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                assertAllEdges(displayedCover(ratio), if (screenshot) "landscape-editor-test.png" else null)
            }
        }
    }
}
