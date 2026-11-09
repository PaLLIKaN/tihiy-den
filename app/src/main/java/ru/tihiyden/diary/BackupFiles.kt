package ru.tihiyden.diary

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BackupFiles {
    const val MIME_TYPE = "application/json"
    fun suggestedName(): String = "tihiy-den-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".json"

    fun save(context: Context, uri: Uri, bytes: ByteArray) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть файл для записи.")
        stream.use { it.write(bytes); it.flush() }
    }

    fun read(context: Context, uri: Uri): DiaryBackup {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл копии.")
        return stream.use(BackupCodec::read)
    }

    fun prepareShare(context: Context, bytes: ByteArray): Uri {
        val directory = File(context.cacheDir, "backups").apply { check(isDirectory || mkdirs()) }
        // Keep each URI immutable while another application may still be reading it.
        val file = File.createTempFile(suggestedName().removeSuffix(".json") + "-", ".json", directory)
        file.outputStream().use { it.write(bytes) }
        return FileProvider.getUriForFile(context, context.packageName + ".backup-files", file)
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, "Резервная копия «Тихий день»")
        clipData = ClipData.newRawUri("Резервная копия", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
