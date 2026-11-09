package ru.tihiyden.diary

import androidx.compose.runtime.saveable.listSaver
import java.time.LocalDate

// Preserve which editor is open across rotation and OS process recreation.
// The editable text fields preserve their drafts with rememberSaveable as well.
val DayEntrySaver = listSaver<DayEntry?, String>(
    save = { item -> item?.let { listOf(it.id, it.title, it.details, it.date.toString(), it.time, it.kind.name, it.done.toString(), it.priority.name, it.dueDate?.toString().orEmpty()) }.orEmpty() },
    restore = { if (it.isEmpty()) null else DayEntry(it[0], it[1], it[2], LocalDate.parse(it[3]), it[4], EntryKind.valueOf(it[5]), it[6].toBoolean(),
        it.getOrNull(7)?.let(TaskPriority::valueOf) ?: TaskPriority.NORMAL, it.getOrNull(8)?.takeIf(String::isNotEmpty)?.let(LocalDate::parse)) },
)
val ThoughtSaver = listSaver<Thought?, String>(
    save = { item -> item?.let { listOf(it.id, it.text, it.createdAt.toString(), it.updatedAt.toString()) }.orEmpty() },
    restore = { if (it.isEmpty()) null else Thought(it[0], it[1], it[2].toLong(), it[3].toLong()) },
)
val MediaEntrySaver = listSaver<MediaEntry?, String>(
    // Covers are recovered from SQLite, and selected-image drafts use a small private file reference.
    save = { item -> item?.let { listOf(it.id, it.title, it.creator, it.kind.name, it.status.name, it.thoughts, it.updatedAt.toString()) }.orEmpty() },
    restore = { if (it.isEmpty()) null else MediaEntry(it[0], it[1], it[2], MediaKind.valueOf(it[3]), MediaStatus.valueOf(it[4]), it[5], it[6].toLong()) },
)
