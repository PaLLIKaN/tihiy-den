package ru.tihiyden.diary

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyQuotesTest {
    @Test fun sameDayHasSameQuoteAcrossCalls() {
        val date = LocalDate.of(2026, 10, 27)
        val expected = quoteForDay(date)
        repeat(20) { assertEquals(expected, quoteForDay(LocalDate.parse("2026-10-27"))) }
    }
    @Test fun everyDayInTheCycleGetsADifferentQuoteIncludingLeapDay() {
        val start = LocalDate.of(2028, 2, 1)
        val quotes = List(dailyQuotes.size) { quoteForDay(start.plusDays(it.toLong())) }
        assertEquals(dailyQuotes.size, quotes.distinct().size)
        assertTrue(quotes.all { it.isNotBlank() })
    }
    @Test fun datesBeforeEpochAndFarFutureHaveValidQuotes() {
        listOf(LocalDate.of(1900, 1, 1), LocalDate.of(1970, 1, 1), LocalDate.of(2099, 12, 31)).forEach {
            assertTrue(quoteForDay(it) in dailyQuotes)
        }
    }
}
