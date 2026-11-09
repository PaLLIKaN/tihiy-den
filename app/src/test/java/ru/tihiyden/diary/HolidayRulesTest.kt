package ru.tihiyden.diary

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HolidayRulesTest {
    private fun day(year: Int, title: String) = HolidayRules.holidays(year).single { it.title == title }.date

    @Test fun programmerUsesThe256thDayIncludingLeapYears() {
        assertEquals(LocalDate.of(2026, 9, 13), day(2026, "День программиста"))
        assertEquals(LocalDate.of(2028, 9, 12), day(2028, "День программиста"))
        assertEquals(LocalDate.of(2100, 9, 13), day(2100, "День программиста"))
    }
    @Test fun movingHolidaysUseTheirWeekdayRulesEachYear() {
        assertEquals(LocalDate.of(2026, 8, 9), day(2026, "День строителя"))
        assertEquals(LocalDate.of(2027, 8, 8), day(2027, "День строителя"))
        assertEquals(LocalDate.of(2026, 11, 29), day(2026, "День матери"))
        assertEquals(LocalDate.of(2027, 11, 28), day(2027, "День матери"))
        assertEquals(LocalDate.of(2026, 7, 6), day(2026, "День архитектора"))
        assertEquals(LocalDate.of(2027, 6, 26), day(2027, "День молодёжи"))
        assertEquals(LocalDate.of(2030, 6, 23), day(2030, "День народных художественных промыслов"))
        assertEquals(LocalDate.of(2030, 6, 16), day(2030, "День медицинского работника"))
    }
    @Test fun catalogHasStableUniqueDatesAndAllThreeCategories() {
        for (year in 2026..2100) {
            val holidays = HolidayRules.holidays(year)
            assertTrue(holidays.size >= 170)
            assertEquals(holidays.size, holidays.map { it.id }.distinct().size)
            assertEquals(holidays.size, holidays.map { it.date to it.title }.distinct().size)
            assertTrue(holidays.all { it.date.year == year && it.title.isNotBlank() })
            assertEquals(14, holidays.count { it.kind == HolidayKind.PUBLIC })
            assertEquals(HolidayKind.entries.toSet(), holidays.map { it.kind }.toSet())
        }
    }
    @Test fun futureAndHistoricalRulesAreNotAppliedPrematurely() {
        assertFalse(HolidayRules.holidays(2028).any { it.title == "День биотехнолога" })
        assertEquals(LocalDate.of(2029, 10, 18), day(2029, "День биотехнолога"))
        assertTrue(HolidayRules.holidays(2025).all { it.kind == HolidayKind.PUBLIC })
    }
    @Test fun categorySwitchesDoNotTurnObservancesIntoPublicHolidays() {
        val settings = HolidaySettings(professional = false)
        assertFalse(settings.shows(HolidayKind.PROFESSIONAL))
        assertTrue(settings.shows(HolidayKind.MEMORIAL))
        assertFalse(HolidaySettings(enabled = false).shows(HolidayKind.PUBLIC))
        assertEquals(HolidayKind.PROFESSIONAL, HolidayRules.holidays(2026).single { it.title == "День учителя" }.kind)
    }
    @Test fun downloadedPublicHolidaysAreAuthoritativeAndDoNotDuplicateRules() {
        val date = LocalDate.of(2026, 1, 1)
        val calendar = ProductionCalendar(2026, LocalDate.of(2025, 9, 30), mapOf(date to ProductionDay(date, 1, "Новый год")))
        val holidays = holidaysForCalendar(2026, calendar)
        assertEquals(listOf("Новый год"), holidays.filter { it.date == date && it.kind == HolidayKind.PUBLIC }.map { it.title })
        assertFalse(holidays.any { it.date == LocalDate.of(2026, 3, 8) && it.kind == HolidayKind.PUBLIC })
        assertTrue(holidays.any { it.title == "День учителя" && it.date.year == 2026 })
        assertEquals(holidays, holidaysForCalendar(2026, calendar))
    }
}
