package ru.tihiyden.diary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Explicit integration check; deterministic regression tests use an injected transport. */
@RunWith(AndroidJUnit4::class)
class HolidayNetworkTest {
    @Test fun realHttpsCalendarCanBeDownloadedAndParsedOnAndroid() {
        assumeTrue("Run with -e verifyNetwork true when internet is available", InstrumentationRegistry.getArguments().getString("verifyNetwork") == "true")
        val bytes = downloadProductionCalendar(2026)
        val calendar = ProductionCalendarCodec.parse(bytes, 2026)
        assertEquals("День народного единства", calendar.days[LocalDate.of(2026, 11, 4)]!!.holiday)
        assertEquals(LocalDate.of(2026, 1, 3), calendar.days[LocalDate.of(2026, 1, 9)]!!.from)
    }
}
