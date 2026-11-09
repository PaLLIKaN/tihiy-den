package ru.tihiyden.diary

import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @Test fun allHuesAndExtremeColorsKeepTextReadable() {
        val seeds = buildList {
            add(0xff000000.toInt()); add(0xffffffff.toInt()); add(DEFAULT_ACCENT)
            for (hue in 0 until 360 step 15) for (saturation in listOf(0f, .3f, 1f)) for (value in listOf(.1f, .6f, 1f)) {
                add(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), saturation, value)))
            }
        }
        seeds.forEach { seed ->
            val scheme = diaryColorScheme(seed)
            val pairs = listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onSecondary to scheme.secondary,
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onPrimaryContainer to scheme.primaryContainer,
            )
            pairs.forEach { (foreground, background) ->
                assertTrue("Low contrast for #${accentHex(seed)}", ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
            }
        }
        assertNotEquals(diaryColorScheme(0xff426697.toInt()).background, diaryColorScheme(0xffa55470.toInt()).background)
        assertEquals("486750", accentHex(DEFAULT_ACCENT))
    }

    @Test fun exactColorAndDefaultSurvivePreferencesReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "theme_test_${UUID.randomUUID()}"
        try {
            assertEquals(DEFAULT_ACCENT, ThemePreferences(context, name).read())
            for (seed in listOf(0xff426697.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), DEFAULT_ACCENT)) {
                assertTrue(ThemePreferences(context, name).save(seed))
                assertEquals(seed, ThemePreferences(context, name).read())
            }
        } finally { context.deleteSharedPreferences(name) }
    }
}
