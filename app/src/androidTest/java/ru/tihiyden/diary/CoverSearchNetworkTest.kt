package ru.tihiyden.diary

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class CoverSearchNetworkTest {
    @Test fun yandexSearchUsesPlainFilmTitleAndReleaseYear() {
        val url = Uri.parse(CoverSearch.yandexImagesUrl("Мастер и Маргарита (фильм, 2024)", "2024"))
        assertEquals("yandex.ru", url.host)
        assertEquals("/images/search", url.path)
        assertEquals("Мастер и Маргарита 2024 кадр из фильма 16:9", url.getQueryParameter("text"))
    }

    @Test fun yandexSearchUsesSeriesTitleAndYear() {
        val url = Uri.parse(CoverSearch.yandexImagesUrl("Во все тяжкие (телесериал, 2008)", "2008", MediaKind.SERIES))
        assertEquals("Во все тяжкие 2008 кадр из сериала 16:9", url.getQueryParameter("text"))
    }

    @Test fun filmArtworkOffersSeveralWideFramesBeforeThePoster() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val film = CoverSearch.search(MediaKind.MOVIE, "Alien", "1979").first { it.title == "Alien (film)" }
        assertEquals("1979", film.releaseYear)
        val artwork = CoverSearch.movieArtwork(film)
        val wide = artwork.takeWhile { it.isWide }
        assertTrue(wide.size >= 2)
        assertEquals(artwork.count { it.isWide }, wide.size)
        assertTrue(film in artwork)
        assertTrue(CoverSearch.download(context, wide.first()).isNotEmpty())
    }

    @Test fun realCatalogsReturnUsableImagesForAllMediaKinds() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val examples = mapOf(MediaKind.BOOK to "The Hobbit", MediaKind.MOVIE to "Интерстеллар", MediaKind.SERIES to "Во все тяжкие")
        for ((kind, title) in examples) {
            val candidates = CoverSearch.search(kind, title)
            val matching = candidates.firstOrNull { it.title.contains(title, ignoreCase = true) }
                ?: error("No match for $kind: ${candidates.map { it.title }}")
            val bytes = CoverSearch.download(context, matching)
            assertTrue(bytes.isNotEmpty())
            val image = Base64.getEncoder().encodeToString(bytes)
            val data = DiaryData(media = listOf(MediaEntry(title = title, kind = kind, cover = image)))
            assertTrue(BackupCodec.decode(BackupCodec.encode(data)).data == data)
        }
    }
}
