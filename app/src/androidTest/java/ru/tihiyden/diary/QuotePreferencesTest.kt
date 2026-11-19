package ru.tihiyden.diary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuotePreferencesTest {
    @Test fun existingInstallDefaultsToDailyAndEveryModeSurvivesReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "quotes-test-${UUID.randomUUID()}"
        try {
            assertEquals(QuoteMode.DAILY, QuotePreferences(context, name).read())
            QuoteMode.entries.forEach { mode ->
                assertTrue(QuotePreferences(context, name).save(mode))
                assertEquals(mode, QuotePreferences(context, name).read())
            }
            assertTrue(context.getSharedPreferences(name, 0).edit().putString("mode", "UNKNOWN").commit())
            assertEquals(QuoteMode.DAILY, QuotePreferences(context, name).read())
        } finally { context.deleteSharedPreferences(name) }
    }
}
