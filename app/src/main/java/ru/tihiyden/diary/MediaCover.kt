package ru.tihiyden.diary

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberCoverImage(cover: String): ImageBitmap? {
    val loaded by produceState<Pair<String, ImageBitmap?>?>(null, cover) {
        value = null
        // Landscape artwork can fill the card width, so retain the stored image resolution.
        val bitmap = withContext(Dispatchers.Default) { runCatching { CoverImages.decode(cover, 800)?.asImageBitmap() }.getOrNull() }
        value = cover to bitmap
    }
    return loaded?.takeIf { it.first == cover }?.second
}

@Composable
fun MediaCover(image: ImageBitmap?, kind: MediaKind, modifier: Modifier = Modifier, preview: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(if (kind == MediaKind.BOOK) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, "Обложка", Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        else Icon(mediaIcon(kind), null, Modifier.size(if (preview) 36.dp else 27.dp), tint = if (kind == MediaKind.BOOK) Sage else Lilac)
    }
}
