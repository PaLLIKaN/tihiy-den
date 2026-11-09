package ru.tihiyden.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val RecordCardShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.recordActions(onEdit: () -> Unit, onDeleteRequest: (() -> Unit)?): Modifier =
    clip(RecordCardShape).combinedClickable(
        role = Role.Button,
        onClick = onEdit,
        onLongClick = onDeleteRequest,
        onLongClickLabel = if (onDeleteRequest != null) "Удалить запись" else null,
    )

internal data class RecordDeletion(
    val question: String,
    val preview: String,
    val delete: () -> Unit,
    val spansDays: Boolean = false,
)

@Composable
internal fun RecordDeletionDialog(deletion: RecordDeletion, canDelete: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(deletion.question) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(deletion.preview.take(240), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (deletion.spansDays) Text("Задача будет удалена со всех дат.")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Удалить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
