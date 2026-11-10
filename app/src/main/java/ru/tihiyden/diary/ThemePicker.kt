package ru.tihiyden.diary

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlin.math.*

fun accentHex(color: Int): String = "%06X".format(java.util.Locale.ROOT, color and 0xffffff)

@Composable
fun ThemeMenuItem(onClick: () -> Unit) {
    val accent = LocalThemeActions.current.accent
    Surface(onClick = onClick, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).background(Color(accent), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            Column(Modifier.weight(1f)) {
                Text("Цветовая гамма", style = MaterialTheme.typography.titleSmall)
                Text("Выбрать свой цвет", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Icon(Icons.Outlined.Palette, null, tint = Sage)
        }
    }
}

@Composable
fun ThemePicker(onDismiss: () -> Unit) {
    val actions = LocalThemeActions.current
    ColorPicker(
        title = "Цветовая гамма",
        description = "Выберите цвет на круге. Фон, кнопки и акценты изменятся вместе.",
        color = actions.accent,
        defaultColor = DEFAULT_ACCENT,
        saving = actions.saving,
        onApply = { actions.save(it, onDismiss) },
        onDismiss = onDismiss,
        preview = { ThemePreview(it) },
    )
}

@Composable
internal fun ColorPicker(
    title: String,
    description: String,
    color: Int,
    defaultColor: Int,
    saving: Boolean,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
    preview: @Composable (Int) -> Unit = {},
) {
    val initial = remember(color) { FloatArray(3).also { AndroidColor.colorToHSV(color, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initial[2]) }
    var hex by rememberSaveable { mutableStateOf(accentHex(color)) }
    val selected = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
    fun update(h: Float, s: Float, v: Float) {
        hue = h; saturation = s; brightness = v
        hex = accentHex(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }
    fun select(color: Int) {
        val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
        update(hsv[0], hsv[1], hsv[2])
    }
    val valid = hex.length == 6
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        icon = { Icon(Icons.Outlined.Palette, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                ColorWheel(hue, saturation, brightness, enabled = !saving, onChange = { h, s -> update(h, s, brightness) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(Color(selected), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    OutlinedTextField(hex, { text ->
                        val filtered = text.removePrefix("#").uppercase(java.util.Locale.ROOT).filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                        hex = filtered
                        if (filtered.length == 6) {
                            val hsv = FloatArray(3).also { AndroidColor.colorToHSV((0xff000000L or filtered.toLong(16)).toInt(), it) }
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        }
                    }, label = { Text("HEX-код") }, prefix = { Text("#") }, singleLine = true, enabled = !saving,
                        isError = !valid, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                }
                Text("Яркость · ${(brightness * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(brightness, { update(hue, saturation, it) }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "Яркость цвета" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(defaultColor, 0xff426697.toInt(), 0xff795b91.toInt(), 0xff387c7b.toInt(), 0xffa55470.toInt(), 0xff996139.toInt()).forEach { color ->
                        Box(Modifier.size(36.dp).background(Color(color), CircleShape)
                            .border(if (color == selected) 3.dp else 1.dp, if (color == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable(enabled = !saving) { select(color) }
                            .semantics { contentDescription = "Цвет: #${accentHex(color)}"; this.selected = color == selected })
                    }
                }
                preview(selected)
                TextButton(enabled = !saving, onClick = { select(defaultColor) }) { Text("Вернуть исходный цвет") }
            }
        },
        confirmButton = { TextButton(enabled = valid && !saving, onClick = {
            if (hex.length == 6 && !saving) onApply(hex.toInt(16) or (0xff shl 24))
        }) { Text(if (saving) "Сохраняем…" else "Применить") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ThemePreview(color: Int) {
    val preview = remember(color) { diaryColorScheme(color) }
    MaterialTheme(colorScheme = preview) {
        Surface(color = preview.background, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, preview.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Так будет выглядеть приложение", color = preview.onBackground, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = preview.primary, contentColor = preview.onPrimary, shape = RoundedCornerShape(12.dp)) { Text("12", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                    Column {
                        Text("В своём ритме", color = preview.onSurface, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif)
                        Text("Дела, мысли и истории", color = preview.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorWheel(hue: Float, saturation: Float, brightness: Float, enabled: Boolean, onChange: (Float, Float) -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val latestHue by rememberUpdatedState(hue)
    val latestSaturation by rememberUpdatedState(saturation)
    val rainbow = remember { listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(228.dp)
            .semantics {
                contentDescription = "Цветовой круг"
                stateDescription = "Оттенок ${hue.roundToInt()} градусов, насыщенность ${(saturation * 100).roundToInt()} процентов"
                customActions = if (enabled) listOf(
                    CustomAccessibilityAction("Следующий оттенок") { latestChange((latestHue + 15f) % 360f, latestSaturation); true },
                    CustomAccessibilityAction("Предыдущий оттенок") { latestChange((latestHue + 345f) % 360f, latestSaturation); true },
                    CustomAccessibilityAction("Повысить насыщенность") { latestChange(latestHue, (latestSaturation + .1f).coerceAtMost(1f)); true },
                    CustomAccessibilityAction("Уменьшить насыщенность") { latestChange(latestHue, (latestSaturation - .1f).coerceAtLeast(0f)); true },
                ) else emptyList()
            }
            .pointerInput(enabled) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = min(size.width, size.height) / 2f - 12.dp.toPx()
                    if ((down.position - center).getDistance() <= radius) {
                        fun choose(position: Offset) {
                            val relative = position - center
                            val angle = ((atan2(relative.y, relative.x) * 180f / PI.toFloat()) + 360f) % 360f
                            latestChange(if (relative.getDistance() < 1f) latestHue else angle, (relative.getDistance() / radius).coerceIn(0f, 1f))
                        }
                        down.consume(); choose(down.position)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (change.pressed) choose(change.position)
                        } while (change.pressed)
                    }
                }
            }) {
            val radius = size.minDimension / 2f - 12.dp.toPx()
            drawCircle(Brush.sweepGradient(rainbow, center), radius)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius)
            drawCircle(Color.Black.copy(alpha = 1f - brightness), radius)
            val radians = hue * PI.toFloat() / 180f
            val marker = center + Offset(cos(radians), sin(radians)) * (saturation * radius)
            drawCircle(Color.Black.copy(alpha = .65f), 9.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 9.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
        }
    }
}
