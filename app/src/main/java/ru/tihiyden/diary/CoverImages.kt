package ru.tihiyden.diary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Small, self-contained JPEGs: no external URI permissions are needed after import. */
object CoverImages {
    const val MAX_BYTES = 96 * 1024
    const val MAX_EDGE = 800
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    private val draftName = Regex("[0-9a-f-]{36}\\.jpg")

    // Run on IO. Bound the source on disk before decoding to avoid loading a full-resolution photo.
    fun importDraft(context: Context, uri: Uri): String =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            writeDraft(context, prepare(context, input, MAX_SOURCE_BYTES))
        }

    fun prepare(context: Context, input: InputStream, maxBytes: Long): ByteArray {
        val source = File.createTempFile("cover-source-", ".image", context.cacheDir)
        try {
            source.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Выберите изображение меньше ${maxBytes / (1024 * 1024)} МБ." }
                    output.write(buffer, 0, count)
                }
            }
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                    val ratio = (MAX_EDGE.toFloat() / max(info.size.width, info.size.height)).coerceAtMost(1f)
                    decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else decodeLegacy(source)
            return try { compress(bitmap) } finally { bitmap.recycle() }
        } finally { source.delete() }
    }

    fun writeDraft(context: Context, bytes: ByteArray): String {
        require(bytes.size in 1..MAX_BYTES)
        val directory = File(context.cacheDir, "cover-drafts").apply { check(isDirectory || mkdirs() || isDirectory) }
        val name = "${UUID.randomUUID()}.jpg"
        File(directory, name).writeBytes(bytes)
        return name
    }

    fun readDraft(context: Context, reference: String): String {
        require(draftName.matches(reference))
        val file = File(File(context.cacheDir, "cover-drafts"), reference)
        require(file.length() in 1..MAX_BYTES.toLong())
        return Base64.getEncoder().encodeToString(file.readBytes()).also(::validate)
    }

    fun validate(cover: String) {
        if (cover.isEmpty()) return
        require(cover.length <= (MAX_BYTES + 2) / 3 * 4)
        val bytes = Base64.getDecoder().decode(cover)
        require(bytes.size in 4..MAX_BYTES)
        require(bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outMimeType == "image/jpeg" && options.outWidth in 1..MAX_EDGE && options.outHeight in 1..MAX_EDGE)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        bitmap.recycle()
    }

    fun decode(cover: String, edge: Int): Bitmap? {
        if (cover.isEmpty() || cover.length > (MAX_BYTES + 2) / 3 * 4) return null
        val bytes = Base64.getDecoder().decode(cover)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..MAX_EDGE || options.outHeight !in 1..MAX_EDGE) return null
        options.inJustDecodeBounds = false
        while (max(options.outWidth, options.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= edge) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun compress(source: Bitmap): ByteArray {
        val ratio = (MAX_EDGE.toFloat() / max(source.width, source.height)).coerceAtMost(1f)
        var bitmap = Bitmap.createBitmap(max(1, (source.width * ratio).roundToInt()), max(1, (source.height * ratio).roundToInt()), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        try {
            while (true) {
                for (quality in listOf(88, 74, 60, 46)) {
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    if (output.size() <= MAX_BYTES) return output.toByteArray()
                }
                val smaller = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width * 3 / 4), max(1, bitmap.height * 3 / 4), true)
                bitmap.recycle()
                bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }

    private fun decodeLegacy(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0)
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options))
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() } }
        catch (e: Exception) { bitmap.recycle(); throw e }
    }
}
