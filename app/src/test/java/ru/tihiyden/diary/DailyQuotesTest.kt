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

    @Test fun personalityQuotesHaveAuthorsAndSourcesAndCoverACompleteCycle() {
        assertEquals(60, personalityQuotes.size)
        val start = LocalDate.of(2028, 2, 20)
        val cycle = List(personalityQuotes.size) { quoteForDay(start.plusDays(it.toLong()), QuoteMode.PEOPLE) }
        assertEquals(personalityQuotes.toSet(), cycle.toSet())
        assertTrue(cycle.all { it.text.isNotBlank() && it.author.isNotBlank() && it.work.isNotBlank() && it.sourceUrl.startsWith("https://") })
        assertEquals(cycle.size, cycle.map { it.text }.distinct().size)
    }

    @Test fun mixedModeAlternatesBothCollectionsAndIsStableAcrossTheEpoch() {
        val start = LocalDate.of(1969, 12, 15)
        val cycle = List(150) { quoteForDay(start.plusDays(it.toLong()), QuoteMode.MIXED) }
        cycle.zipWithNext().forEach { (first, second) -> assertTrue(first.author.isBlank() != second.author.isBlank()) }
        assertTrue(cycle.filter { it.author.isBlank() }.all { it.text in dailyQuotes })
        assertTrue(cycle.filter { it.author.isNotBlank() }.all { it in personalityQuotes })
        QuoteMode.entries.forEach { mode ->
            val date = LocalDate.of(2026, 11, 17)
            repeat(3) { assertEquals(quoteForDay(date, mode), quoteForDay(date, mode)) }
        }
    }

    @Test fun dailyModePreservesExistingSelection() {
        val date = LocalDate.of(2026, 11, 17)
        assertEquals(CalendarQuote(quoteForDay(date)), quoteForDay(date, QuoteMode.DAILY))
    }
}
