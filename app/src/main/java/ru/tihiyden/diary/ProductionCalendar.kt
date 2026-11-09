package ru.tihiyden.diary

import android.content.Context
import android.util.AtomicFile
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.net.URL
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

data class ProductionDay(val date: LocalDate, val type: Int, val holiday: String? = null, val from: LocalDate? = null) {
    val label: String get() = when {
        from != null -> "Перенос выходного с ${from.format(DateTimeFormatter.ofPattern("d MMMM", Russian))}"
        holiday != null -> "Нерабочий праздничный день"
        type == 2 -> "Сокращённый рабочий день"
        type == 3 -> "Рабочий день по производственному календарю"
        else -> "Выходной день"
    }
}
data class ProductionCalendar(val year: Int, val published: LocalDate, val days: Map<LocalDate, ProductionDay>)

object ProductionCalendarCodec {
    const val MAX_BYTES = 256 * 1024
    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun parse(bytes: ByteArray, year: Int): ProductionCalendar {
        require(year in 1900..2100 && bytes.size in 1..MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val parser = Xml.newPullParser().apply { setInput(StringReader(text)) }
        val path = mutableListOf<String>()
        val titles = mutableMapOf<String, String>()
        data class RawDay(val date: LocalDate, val type: Int, val holiday: String?, val from: LocalDate?)
        val days = mutableMapOf<LocalDate, RawDay>()
        var published: LocalDate? = null
        var rootSeen = false
        var holidaysSeen = false
        var daysSeen = false
        fun attr(name: String): String = requireNotNull(parser.getAttributeValue(null, name))
        fun date(value: String): LocalDate {
            require(value.matches(Regex("[0-9]{2}\\.[0-9]{2}")))
            return LocalDate.of(year, value.take(2).toInt(), value.takeLast(2).toInt())
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    path.add(parser.name)
                    when (path.joinToString("/")) {
                        "calendar" -> {
                            require(!rootSeen && attr("year").toInt() == year && attr("country") == "ru" && attr("lang") == "ru")
                            rootSeen = true
                            published = LocalDate.parse(attr("date"), DateTimeFormatter.ofPattern("uuuu.MM.dd").withResolverStyle(java.time.format.ResolverStyle.STRICT))
                        }
                        "calendar/holidays" -> { require(!holidaysSeen); holidaysSeen = true }
                        "calendar/days" -> { require(!daysSeen); daysSeen = true }
                        "calendar/holidays/holiday" -> {
                            require(titles.size < 64)
                            val id = attr("id")
                            val title = attr("title")
                            require(id.matches(Regex("[0-9]{1,4}")) && title.isNotBlank() && title.length <= 200)
                            require(titles.put(id, title) == null)
                        }
                        "calendar/days/day" -> {
                            val day = date(attr("d"))
                            val type = attr("t").toInt()
                            val h = parser.getAttributeValue(null, "h")
                            val from = parser.getAttributeValue(null, "f")?.let(::date)
                            require(type in 1..3 && (h == null || type == 1) && (from == null || type == 1))
                            require(days.put(day, RawDay(day, type, h, from)) == null)
                        }
                        else -> error("Unexpected calendar structure")
                    }
                }
                XmlPullParser.END_TAG -> { require(path.lastOrNull() == parser.name); path.removeAt(path.lastIndex) }
                XmlPullParser.TEXT -> require(parser.text.isBlank())
                XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> error("Unsupported XML declaration")
            }
            event = parser.next()
        }
        require(rootSeen && holidaysSeen && daysSeen && path.isEmpty() && titles.isNotEmpty() && days.isNotEmpty())
        val result = days.mapValues { (_, item) ->
            ProductionDay(item.date, item.type, item.holiday?.let { requireNotNull(titles[it]) }, item.from)
        }
        require(result.values.any { it.holiday != null })
        return ProductionCalendar(year, requireNotNull(published), result)
    }
}

data class LoadedProduction(val calendar: ProductionCalendar?, val downloadedAt: Long = 0)

/** A separate, replaceable cache. Downloaded events never enter the personal diary tables. */
class HolidayRepository(
    private val context: Context,
    directory: String = "holiday-calendars",
    private val fetch: (Int) -> ByteArray = ::downloadProductionCalendar,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object { private val cacheLock = Any() }
    private val root = File(context.filesDir, directory).apply { check(isDirectory || mkdirs()) }
    private val attempts = context.getSharedPreferences(directory, Context.MODE_PRIVATE)

    fun load(year: Int): LoadedProduction {
        return synchronized(cacheLock) {
        require(year in 1900..2100)
        val file = File(root, "$year.xml")
        try {
            if (file.exists() || File(root, "$year.xml.bak").exists()) {
                val bytes = AtomicFile(file).openRead().use(ProductionCalendarCodec::read)
                return@synchronized LoadedProduction(ProductionCalendarCodec.parse(bytes, year), file.lastModified())
            }
        } catch (_: Exception) { /* Keep the last packaged calendar available if the cache is corrupt. */ }
        val bundled = runCatching { context.assets.open("holidays/$year.xml").use { ProductionCalendarCodec.parse(ProductionCalendarCodec.read(it), year) } }.getOrNull()
        LoadedProduction(bundled)
        }
    }

    fun shouldRefresh(year: Int, loaded: LoadedProduction): Boolean {
        val time = now()
        val success = loaded.downloadedAt
        val attempt = attempts.getLong("attempt-$year", 0)
        fun elapsed(stamp: Long, interval: Long) = stamp == 0L || time < stamp || time - stamp >= interval
        return elapsed(success, 7L * 24 * 60 * 60 * 1000) && elapsed(attempt, 60L * 60 * 1000)
    }

    fun refresh(year: Int): LoadedProduction {
        require(year in 1900..2100)
        attempts.edit().putLong("attempt-$year", now()).commit()
        val bytes = fetch(year)
        val calendar = ProductionCalendarCodec.parse(bytes, year)
        return synchronized(cacheLock) {
        val previous = load(year).calendar
        require(previous == null || calendar.published >= previous.published)
        val file = File(root, "$year.xml")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        check(file.setLastModified(now()))
        LoadedProduction(calendar, file.lastModified())
        }
    }
}

fun downloadProductionCalendar(year: Int): ByteArray {
    require(year in 1900..2100)
    val sources = listOf("https://xmlcalendar.ru/data/ru/$year/calendar.xml", "https://raw.githubusercontent.com/xmlcalendar/data/master/ru/$year/calendar.xml")
    var lastError: Exception? = null
    for (source in sources) {
        val connection = URL(source).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 6000; connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/xml, text/xml")
            connection.setRequestProperty("User-Agent", "TihiyDen/0.5.0")
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use(ProductionCalendarCodec::read)
            // Try the mirror as well if a server returns HTML or a malformed payload.
            ProductionCalendarCodec.parse(bytes, year)
            return bytes
        } catch (e: Exception) { lastError = e }
        finally { connection.disconnect() }
    }
    throw java.io.IOException("Calendar unavailable", lastError)
}
