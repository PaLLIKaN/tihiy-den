package ru.tihiyden.diary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HolidayRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun xml(year: Int = 2026) = context.assets.open("holidays/$year.xml").use { it.readBytes() }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid calendar to be rejected") } catch (_: Exception) { }
    }
    private fun withDirectory(action: (String) -> Unit) {
        val name = "holiday_test_${UUID.randomUUID()}"
        try { action(name) }
        finally {
            // Only this generated fixture directory is removed; personal data lives elsewhere.
            File(context.filesDir, name).deleteRecursively()
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun bundledYearsIncludeHolidayNamesTransfersAndShortWorkingSaturdays() {
        val current = ProductionCalendarCodec.parse(xml(), 2026)
        assertEquals("День народного единства", current.days[LocalDate.of(2026, 11, 4)]!!.holiday)
        assertEquals(LocalDate.of(2026, 1, 3), current.days[LocalDate.of(2026, 1, 9)]!!.from)
        assertEquals(2, current.days[LocalDate.of(2026, 11, 3)]!!.type)
        val next = ProductionCalendarCodec.parse(xml(2027), 2027)
        assertEquals(2, next.days[LocalDate.of(2027, 2, 20)]!!.type)
        assertEquals(LocalDate.of(2027, 2, 20), next.days[LocalDate.of(2027, 2, 22)]!!.from)
    }

    @Test fun invalidOrOversizedRemoteFilesAreRejected() {
        val original = xml().toString(Charsets.UTF_8)
        listOf(
            original.replace("year=\"2026\"", "year=\"2025\""),
            original.replace("country=\"ru\"", "country=\"kz\""),
            original.replace("h=\"8\"", "h=\"999\""),
            original.replace("d=\"11.04\"", "d=\"02.30\""),
            original.replace("t=\"2\"", "t=\"4\""),
            original.replace("</days>", "<day d=\"11.04\" t=\"1\"/></days>"),
            original.replace("<calendar", "<!DOCTYPE calendar [<!ENTITY x SYSTEM 'file:///private'>]><calendar"),
            original.replace("</calendar>", "</calendar><calendar/>"),
            "<html>Not a calendar</html>",
        ).forEach { value -> invalid { ProductionCalendarCodec.parse(value.toByteArray(), 2026) } }
        invalid { ProductionCalendarCodec.parse(byteArrayOf(0xc3.toByte(), 0x28), 2026) }
        invalid { ProductionCalendarCodec.read(ByteArrayInputStream(ByteArray(ProductionCalendarCodec.MAX_BYTES + 1))) }
    }

    @Test fun refreshSurvivesReopeningAndUsesWeeklyScheduleWithoutDuplicates() = withDirectory { name ->
        var calls = 0
        var time = System.currentTimeMillis()
        val fetch: (Int) -> ByteArray = { calls++; xml(it) }
        val repository = HolidayRepository(context, name, fetch) { time }
        assertNotNull(repository.load(2026).calendar) // Available before the first network request.
        assertTrue(repository.shouldRefresh(2026, repository.load(2026)))
        val first = repository.refresh(2026)
        assertEquals(1, calls)
        val reopened = HolidayRepository(context, name, fetch) { time }
        assertEquals(first.calendar, reopened.load(2026).calendar)
        assertFalse(reopened.shouldRefresh(2026, reopened.load(2026)))
        time += 7L * 24 * 60 * 60 * 1000
        assertTrue(reopened.shouldRefresh(2026, reopened.load(2026)))
        assertEquals(first.calendar, reopened.refresh(2026).calendar)
        assertEquals(2, calls)
        assertEquals(holidaysForCalendar(2026, first.calendar), holidaysForCalendar(2026, reopened.load(2026).calendar))
    }

    @Test fun failedMalformedAndOlderUpdatesLeaveLastGoodCalendarIntact() = withDirectory { name ->
        val good = xml()
        var response = good
        var unavailable = false
        val repository = HolidayRepository(context, name, { if (unavailable) throw IOException("offline") else response })
        val saved = repository.refresh(2026)
        response = "<html>Error</html>".toByteArray()
        invalid { repository.refresh(2026) }
        assertEquals(saved, repository.load(2026))
        response = good.toString(Charsets.UTF_8).replace("2025.09.30", "2025.01.01").toByteArray()
        invalid { repository.refresh(2026) }
        assertEquals(saved, repository.load(2026))
        unavailable = true
        invalid { repository.refresh(2026) }
        assertEquals(saved, repository.load(2026))
    }

    @Test fun missingYearAndConnectionFailureUseRulesWithoutInventingTransfers() = withDirectory { name ->
        var time = System.currentTimeMillis()
        val repository = HolidayRepository(context, name, { throw IOException("offline") }) { time }
        assertNull(repository.load(2030).calendar)
        invalid { repository.refresh(2030) }
        assertFalse(repository.shouldRefresh(2030, repository.load(2030)))
        time += 60L * 60 * 1000
        assertTrue(repository.shouldRefresh(2030, repository.load(2030)))
        val holidays = holidaysForCalendar(2030, null)
        assertTrue(holidays.any { it.title == "День учителя" && it.date.year == 2030 })
        assertTrue(holidays.any { it.kind == HolidayKind.PUBLIC && it.date == LocalDate.of(2030, 1, 1) })
    }

    @Test fun categoryPreferencesSurviveReopening() {
        val name = "holiday_prefs_${UUID.randomUUID()}"
        try {
            assertEquals(HolidaySettings(), HolidayPreferences(context, name).read())
            val settings = HolidaySettings(enabled = false, memorial = false, professional = false, autoUpdate = false, dayTypes = false)
            assertTrue(HolidayPreferences(context, name).save(settings))
            assertEquals(settings, HolidayPreferences(context, name).read())
        } finally { context.deleteSharedPreferences(name) }
    }
}
