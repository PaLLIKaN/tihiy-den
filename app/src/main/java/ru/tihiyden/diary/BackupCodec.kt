package ru.tihiyden.diary

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate

data class DiaryBackup(val data: DiaryData, val exportedAt: Instant)
enum class RestoreMode { MERGE_MISSING, REPLACE_ALL }
data class RestoreResult(val count: Int, val data: DiaryData)

object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val FORMAT = "ru.tihiyden.diary.backup"
    private const val VERSION = 3
    private const val MAX_RECORDS = 100_000

    fun encode(data: DiaryData, exportedAt: Instant = Instant.now()): ByteArray {
        validate(data)
        val root = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("exportedAt", exportedAt.toString())
            .put("entries", array(data.entries) { item ->
                JSONObject().put("id", item.id).put("title", item.title).put("details", item.details)
                    .put("date", item.date.toString()).put("time", item.time)
                    .put("kind", item.kind.name).put("done", item.done)
                    .put("priority", item.priority.name).put("dueDate", item.dueDate?.toString().orEmpty())
            })
            .put("thoughts", array(data.thoughts) { item ->
                JSONObject().put("id", item.id).put("text", item.text)
                    .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
            })
            .put("media", array(data.media) { item ->
                JSONObject().put("id", item.id).put("title", item.title).put("creator", item.creator)
                    .put("kind", item.kind.name).put("status", item.status.name)
                    .put("thoughts", item.thoughts).put("updatedAt", item.updatedAt)
                    .put("cover", item.cover)
            })
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Копия больше 20 МБ. Обратитесь за помощью с экспортом." }
        return bytes
    }

    fun decode(bytes: ByteArray): DiaryBackup {
        require(bytes.size <= MAX_BYTES) { "Размер файла превышает 20 МБ." }
        try {
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            val parser = JSONTokener(text)
            val root = parser.nextValue()
            require(root is JSONObject && parser.nextClean() == '\u0000')
            require(root.string("format") == FORMAT) { "Это не резервная копия «Тихого дня»." }
            val version = root.integer("version")
            require(version in 1L..VERSION.toLong()) { "Версия копии не поддерживается. Обновите приложение." }
            val exportedAt = Instant.parse(root.string("exportedAt"))
            require(exportedAt.toEpochMilli() >= 0)
            val entries = objects(root.getJSONArray("entries")).map { item ->
                val done = item.get("done")
                require(done is Boolean)
                DayEntry(item.string("id"), item.string("title"), item.string("details"), LocalDate.parse(item.string("date")), item.string("time"), EntryKind.valueOf(item.string("kind")), done,
                    priority = if (version >= 3) TaskPriority.valueOf(item.string("priority")) else TaskPriority.NORMAL,
                    dueDate = if (version >= 3) item.string("dueDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse) else null)
            }
            val thoughts = objects(root.getJSONArray("thoughts")).map { item ->
                Thought(item.string("id"), item.string("text"), item.integer("createdAt"), item.integer("updatedAt"))
            }
            val media = objects(root.getJSONArray("media")).map { item ->
                MediaEntry(item.string("id"), item.string("title"), item.string("creator"), MediaKind.valueOf(item.string("kind")), MediaStatus.valueOf(item.string("status")), item.string("thoughts"), item.integer("updatedAt"), if (version >= 2) item.string("cover") else "")
            }
            val data = DiaryData(entries, thoughts, media)
            validate(data)
            return DiaryBackup(data, exportedAt)
        } catch (e: StackOverflowError) {
            throw IllegalArgumentException("Файл повреждён: слишком сложная структура. Данные не изменены.", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e.message?.takeIf { it.startsWith("Это не") || it.startsWith("Версия") }
                ?: "Файл повреждён или содержит некорректные записи. Данные не изменены.", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("Не удалось прочитать копию. Проверьте, что выбран правильный JSON-файл.", e)
        }
    }

    fun read(input: InputStream): DiaryBackup {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= MAX_BYTES) { "Размер файла превышает 20 МБ. Данные не изменены." }
            bytes.write(buffer, 0, count)
        }
        return decode(bytes.toByteArray())
    }

    fun write(output: OutputStream, data: DiaryData) { output.write(encode(data)); output.flush() }

    fun validate(data: DiaryData) {
        require(data.entries.size.toLong() + data.thoughts.size + data.media.size <= MAX_RECORDS)
        checkIds(data.entries.map { it.id }); checkIds(data.thoughts.map { it.id }); checkIds(data.media.map { it.id })
        data.entries.forEach {
            require(it.title.isNotBlank())
            require(it.time.isEmpty() || it.time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
            it.validateSchedule()
        }
        data.thoughts.forEach { require(it.text.isNotBlank()); require(it.createdAt >= 0 && it.updatedAt >= 0) }
        data.media.forEach { require(it.title.isNotBlank()); require(it.updatedAt >= 0); CoverImages.validate(it.cover) }
    }

    private fun checkIds(ids: List<String>) {
        require(ids.all { it.isNotBlank() && it.length <= 128 })
        require(ids.distinct().size == ids.size)
    }

    private fun JSONObject.string(key: String): String = get(key).also { require(it is String) } as String
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    private fun objects(array: JSONArray): List<JSONObject> {
        require(array.length() <= MAX_RECORDS)
        return List(array.length()) { array.getJSONObject(it) }
    }
    private fun <T> array(items: List<T>, encode: (T) -> JSONObject): JSONArray = JSONArray().apply { items.forEach { put(encode(it)) } }
}
