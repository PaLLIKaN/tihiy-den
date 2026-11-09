package ru.tihiyden.diary

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val COVER_QUERY_LIMIT = 160

internal data class CoverCandidate(
    val title: String,
    val description: String,
    val image: URI,
    val source: String,
    val sourceUrl: String,
    val releaseYear: String? = null,
    val aspectRatio: Double = 0.0,
) {
    val isWide: Boolean get() = aspectRatio >= 1.45
    val displayTitle: String get() = if (releaseYear != null && releaseYear !in title) "$title · $releaseYear" else title
}

internal object CoverSearch {
    private const val MAX_RESULTS = 6
    private const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_IMAGE_BYTES = 6L * 1024 * 1024
    private const val USER_AGENT = "TihiyDen/0.8 (Android personal diary; ru.tihiyden.diary)"
    private val imageHosts = setOf("upload.wikimedia.org", "thumb.wikimedia.org", "covers.openlibrary.org")
    private val cyrillic = Regex("[а-яё]", RegexOption.IGNORE_CASE)
    private val mediaTerms = mapOf(
        MediaKind.SERIES to Regex("сериал|\\b(?:television|tv|streaming|web|anime) (?:\\w+ )?series\\b", RegexOption.IGNORE_CASE),
        MediaKind.MOVIE to Regex("фильм|\\b(?:film|movie)\\b", RegexOption.IGNORE_CASE),
        MediaKind.BOOK to Regex("\\bроман\\b|книга|повесть|сборник|рассказ|манга|комикс|\\b(?:book|novel|novella|short story|manga)\\b", RegexOption.IGNORE_CASE),
    )
    private val seriesPart = Regex("\\b(?:сезон|серия|эпизод|season|episode)\\b", RegexOption.IGNORE_CASE)
    private val releaseYear = Regex("(?<![0-9])(?:18[89][0-9]|19[0-9]{2}|20[0-9]{2})(?![0-9])")
    private val stillName = Regex("(?:кадр|скриншот|скрин|screenshot|screen[ _-]?(?:shot|cap|grab)|film[ _-]?still|in a scene|backdrop|production still)", RegexOption.IGNORE_CASE)
    private val unrelatedFile = Regex("\\b(?:cast|crew|premiere|director|logo|concept|illustration|cosplay|gargoyle|trailer|акт[её]ры|премьера|логотип)\\b", RegexOption.IGNORE_CASE)
    private val nonWords = Regex("[^\\p{L}\\p{N}]+")
    private val rasterImages = setOf("image/jpeg", "image/png", "image/webp")

    suspend fun search(kind: MediaKind, title: String, year: String = ""): List<CoverCandidate> = withContext(Dispatchers.IO) {
        val query = title.trim().take(COVER_QUERY_LIMIT)
        if (query.isEmpty()) return@withContext emptyList()
        val russian = cyrillic.containsMatchIn(query)
        val lookups = buildList<suspend () -> List<CoverCandidate>> {
            if (kind == MediaKind.BOOK && !russian) add { openLibrary(query) }
            add { wikipedia(if (russian) "ru" else "en", kind, query, year) }
            if (kind == MediaKind.BOOK && russian) add { openLibrary(query) }
            if (!russian) add { wikipedia("ru", kind, query, year) }
        }
        var failure: Exception? = null
        for (lookup in lookups) {
            try {
                val results = lookup()
                if (results.isNotEmpty()) return@withContext results
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        if (failure != null) throw IOException("Cover search unavailable", failure)
        emptyList()
    }

    suspend fun download(context: Context, candidate: CoverCandidate): ByteArray = withContext(Dispatchers.IO) {
        fetch(candidate.image, imageHosts, "image/*") { connection, input ->
            require(connection.contentType.orEmpty().startsWith("image/", ignoreCase = true))
            require(connection.contentLengthLong <= MAX_IMAGE_BYTES)
            CoverImages.prepare(context, input, MAX_IMAGE_BYTES)
        }
    }

    private suspend fun wikipedia(language: String, kind: MediaKind, title: String, year: String): List<CoverCandidate> {
        val host = "$language.wikipedia.org"
        val search = listOf("intitle:${title.replace('"', ' ')}", year).filter(String::isNotBlank).joinToString(" ")
        val url = Uri.parse("https://$host/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrsearch", search)
            .appendQueryParameter("gsrlimit", "12")
            .appendQueryParameter("prop", "pageimages|pageterms|info")
            .appendQueryParameter("inprop", "url")
            .appendQueryParameter("wbptterms", "description")
            .appendQueryParameter("piprop", "thumbnail")
            .appendQueryParameter("pithumbsize", "480")
            .appendQueryParameter("pilicense", "any")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2").build()
        val pages = json(url, host).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).mapNotNull(pages::optJSONObject)
            .sortedBy { it.optInt("index", Int.MAX_VALUE) }
            .mapNotNull { page ->
                val name = page.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val description = page.optJSONObject("terms")?.optJSONArray("description")?.optString(0).orEmpty()
                val qualifier = name.substringAfterLast('(', "").substringBefore(')')
                val type = classify(qualifier) ?: classify(description)
                if (type != kind || (kind == MediaKind.SERIES && (seriesPart.containsMatchIn(qualifier) || seriesPart.containsMatchIn(description)))) return@mapNotNull null
                val movieYear = if (kind == MediaKind.MOVIE) releaseYear.find(qualifier)?.value ?: releaseYear.find(description)?.value else null
                if (year.isNotEmpty() && movieYear != year) return@mapNotNull null
                val thumbnail = page.optJSONObject("thumbnail") ?: return@mapNotNull null
                val image = trustedUri(thumbnail.optString("source").substringBefore('?'), imageHosts)
                    ?: return@mapNotNull null
                val article = trustedUri(page.optString("fullurl"), setOf(host)) ?: return@mapNotNull null
                CoverCandidate(name, description, image, "Википедия", article.toString(), movieYear, thumbnail.aspectRatio())
            }.distinctBy { it.image }.take(MAX_RESULTS)
    }

    suspend fun movieArtwork(movie: CoverCandidate): List<CoverCandidate> = withContext(Dispatchers.IO) {
        val host = URI(movie.sourceUrl).host
        if (host !in setOf("ru.wikipedia.org", "en.wikipedia.org")) return@withContext listOf(movie)
        val stills = try { wikipediaStills(host, movie) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
        val ranked = stills.filter { it.image != movie.image }.distinctBy { it.image }
            .sortedBy { abs(it.aspectRatio - 16.0 / 9.0) }.take(MAX_RESULTS - 1)
        (ranked + movie).sortedWith(compareByDescending<CoverCandidate> { it.isWide }.thenBy { abs(it.aspectRatio - 16.0 / 9.0) })
    }

    private suspend fun wikipediaStills(host: String, movie: CoverCandidate): List<CoverCandidate> {
        val url = Uri.parse("https://$host/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("titles", movie.title)
            .appendQueryParameter("generator", "images")
            .appendQueryParameter("gimlimit", "40")
            .appendQueryParameter("prop", "imageinfo")
            .appendQueryParameter("iiprop", "url|size|mime")
            .appendQueryParameter("iiurlwidth", "500")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2").build()
        val files = json(url, host).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until files.length()).mapNotNull { index ->
            val file = files.optJSONObject(index) ?: return@mapNotNull null
            val fileName = file.optString("title").substringAfter(':').substringBeforeLast('.')
            val info = file.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val aspectRatio = info.aspectRatio()
            if (info.optString("mime") !in rasterImages || info.optInt("width") < 300 || aspectRatio < 1.45) return@mapNotNull null
            val image = trustedUri(info.optString("thumburl").ifBlank { info.optString("url") }.substringBefore('?'), imageHosts)
                ?: return@mapNotNull null
            if (!matchesMovieStill(fileName, image, host.substringBefore('.'), movie)) return@mapNotNull null
            movie.copy(image = image, aspectRatio = aspectRatio)
        }
    }

    private fun matchesMovieStill(fileName: String, image: URI, language: String, movie: CoverCandidate): Boolean {
        val title = words(movieTitleWithoutQualifier(movie.title))
        val name = words(fileName)
        if (title.isBlank() || unrelatedFile.containsMatchIn(name)) return false
        val fileYear = releaseYear.find(name.removePrefix(title))?.value
        if (movie.releaseYear != null && fileYear != null && fileYear != movie.releaseYear) return false
        val startsWithTitle = name == title || name.startsWith("$title ")
        val localFile = image.path.startsWith("/wikipedia/$language/")
        if (localFile && startsWithTitle) return true
        if (!stillName.containsMatchIn(fileName)) return false
        return startsWithTitle || " scene from $title " in " $name " || " кадр из фильма $title " in " $name "
    }

    private fun words(text: String): String = text.lowercase(java.util.Locale.ROOT).replace(nonWords, " ").trim()

    private fun JSONObject.aspectRatio(): Double {
        val height = optInt("height")
        return if (height > 0) optInt("width").toDouble() / height else 0.0
    }

    fun yandexImagesUrl(title: String, year: String): String {
        val qualifier = title.substringAfterLast('(', "").substringBefore(')')
        val cleanTitle = movieTitleWithoutQualifier(title)
        val effectiveYear = year.ifBlank { releaseYear.find(qualifier)?.value.orEmpty() }
        val terms = listOf(cleanTitle, effectiveYear.takeUnless { it in cleanTitle }.orEmpty(), "кадр из фильма 16:9")
        return Uri.parse("https://yandex.ru/images/search").buildUpon()
            .appendQueryParameter("text", terms.filter(String::isNotBlank).joinToString(" ")).build().toString()
    }

    private fun movieTitleWithoutQualifier(title: String): String {
        val qualifier = title.substringAfterLast('(', "").substringBefore(')')
        return if (title.endsWith(')') && (classify(qualifier) == MediaKind.MOVIE || releaseYear.matches(qualifier))) {
            title.substringBeforeLast('(').trim()
        } else title.trim()
    }

    private fun classify(text: String): MediaKind? = mediaTerms.entries.mapNotNull { (kind, pattern) ->
        pattern.find(text)?.let { it.range.first to kind }
    }.minByOrNull { it.first }?.second

    private suspend fun openLibrary(title: String): List<CoverCandidate> {
        val host = "openlibrary.org"
        val url = Uri.parse("https://$host/search.json").buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("fields", "key,title,author_name,cover_i,first_publish_year")
            .appendQueryParameter("limit", "12").build()
        val docs = json(url, host).optJSONArray("docs") ?: throw IOException("Missing Open Library results")
        return (0 until docs.length()).mapNotNull { index ->
            val book = docs.optJSONObject(index) ?: return@mapNotNull null
            val name = book.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val imageId = book.optLong("cover_i").takeIf { it > 0 } ?: return@mapNotNull null
            val key = book.optString("key").takeIf { it.matches(Regex("/works/OL[0-9]+W")) } ?: return@mapNotNull null
            val author = book.optJSONArray("author_name")?.optString(0).orEmpty()
            val year = book.optInt("first_publish_year").takeIf { it > 0 }?.toString().orEmpty()
            CoverCandidate(name, listOf(author, year).filter(String::isNotBlank).joinToString(" · "),
                URI("https://covers.openlibrary.org/b/id/$imageId-L.jpg?default=false"), "Open Library", "https://$host$key")
        }.distinctBy { it.image }.take(MAX_RESULTS)
    }

    private suspend fun json(url: Uri, host: String): JSONObject = fetch(URI(url.toString()), setOf(host), "application/json") { _, input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_JSON_BYTES)
            output.write(buffer, 0, count)
        }
        JSONObject(output.toString(Charsets.UTF_8.name())).also {
            if (it.has("error")) throw IOException("Cover catalog returned an error")
        }
    }

    private suspend fun <T> fetch(start: URI, allowedHosts: Set<String>, accept: String, read: (HttpsURLConnection, InputStream) -> T): T {
        var url = start
        repeat(4) {
            kotlin.coroutines.coroutineContext.ensureActive()
            require(trustedUri(url.toString(), allowedHosts) != null)
            val connection = (url.toURL().openConnection() as HttpsURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                when (connection.responseCode) {
                    200 -> return connection.inputStream.use { read(connection, it) }
                    301, 302, 303, 307, 308 -> url = url.resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect"))
                    else -> throw IOException("Cover catalog returned HTTP ${connection.responseCode}")
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    private fun trustedUri(value: String, allowedHosts: Set<String>): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.host in allowedHosts && it.userInfo == null && it.port in listOf(-1, 443)
    }
}
