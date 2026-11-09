package ru.tihiyden.diary

import android.app.Application
import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DEFAULT_ACCENT: Int = -12032176 // #486750

class ThemePreferences(context: Context, name: String = "appearance") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun read(): Int = preferences.getInt("accent", DEFAULT_ACCENT) or (0xff shl 24)
    fun save(color: Int): Boolean = preferences.edit().putInt("accent", color or (0xff shl 24)).commit()
}

class ThemeViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ThemePreferences(application)
    private val _accent = MutableStateFlow(preferences.read())
    val accent = _accent.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = false }
    fun save(color: Int, onSaved: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { preferences.save(color) }) {
                    _accent.value = color
                    onSaved()
                } else _error.value = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = true }
            finally { _saving.value = false }
        }
    }
}

/** The seed stays exact; text and controls receive darker, readable companion tones. */
fun diaryColorScheme(accent: Int): ColorScheme {
    val seed = Color(accent or (0xff shl 24))
    var primary = seed
    while ((1.05f / (primary.luminance() + .05f)) < 4.6f) primary = lerp(primary, Color.Black, .05f)
    val secondary = lerp(primary, Color(0xff716078), .32f)
    val ink = lerp(primary, Color.Black, .62f)
    val muted = lerp(primary, Color(0xff444444), .6f)
    val background = lerp(seed, Color.White, .965f)
    val card = lerp(seed, Color.White, .99f)
    val container = lerp(seed, Color.White, .86f)
    val outlineVariant = lerp(seed, Color.White, .78f)
    return lightColorScheme(
        primary = primary, onPrimary = Color.White,
        primaryContainer = container, onPrimaryContainer = ink,
        inversePrimary = lerp(seed, Color.White, .6f),
        secondary = secondary, onSecondary = Color.White,
        secondaryContainer = lerp(secondary, Color.White, .9f), onSecondaryContainer = ink,
        tertiary = secondary, onTertiary = Color.White,
        tertiaryContainer = lerp(secondary, Color.White, .86f), onTertiaryContainer = ink,
        background = background, onBackground = ink,
        surface = background, onSurface = ink,
        surfaceVariant = lerp(seed, Color.White, .91f), onSurfaceVariant = muted,
        surfaceTint = primary, inverseSurface = ink, inverseOnSurface = background,
        outline = lerp(primary, Color(0xff666666), .35f), outlineVariant = outlineVariant,
        surfaceBright = card, surfaceDim = lerp(seed, Color.White, .86f),
        surfaceContainerLowest = card, surfaceContainerLow = background,
        surfaceContainer = lerp(seed, Color.White, .94f),
        surfaceContainerHigh = lerp(seed, Color.White, .91f),
        surfaceContainerHighest = lerp(seed, Color.White, .88f),
    )
}

data class ThemeActions(val accent: Int, val saving: Boolean, val save: (Int, () -> Unit) -> Unit)
val LocalThemeActions = staticCompositionLocalOf { ThemeActions(DEFAULT_ACCENT, false) { _, done -> done() } }

@Composable
fun DiaryTheme(accent: Int = DEFAULT_ACCENT, savingTheme: Boolean = false, onAccentChange: (Int, () -> Unit) -> Unit = { _, done -> done() }, content: @Composable () -> Unit) {
    val scheme = remember(accent) { diaryColorScheme(accent) }
    CompositionLocalProvider(LocalThemeActions provides ThemeActions(accent, savingTheme, onAccentChange)) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)),
            content = content,
        )
    }
}

val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Sage: Color @Composable get() = MaterialTheme.colorScheme.primary
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
val Lilac: Color @Composable get() = MaterialTheme.colorScheme.secondary
