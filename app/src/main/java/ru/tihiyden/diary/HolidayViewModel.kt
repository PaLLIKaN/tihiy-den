package ru.tihiyden.diary

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class HolidayPreferences(context: Context, name: String = "holiday-settings") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun read() = HolidaySettings(
        preferences.getBoolean("enabled", true), preferences.getBoolean("public", true),
        preferences.getBoolean("memorial", true), preferences.getBoolean("professional", true),
        preferences.getBoolean("dayTypes", true), preferences.getBoolean("autoUpdate", true),
        HolidayKind.entries.filter { preferences.contains(colorKey(it)) }
            .associateWith { preferences.getInt(colorKey(it), it.defaultColor) },
    )
    fun save(value: HolidaySettings): Boolean = preferences.edit()
        .putBoolean("enabled", value.enabled).putBoolean("public", value.public)
        .putBoolean("memorial", value.memorial).putBoolean("professional", value.professional)
        .putBoolean("dayTypes", value.dayTypes).putBoolean("autoUpdate", value.autoUpdate)
        .apply {
            HolidayKind.entries.forEach { kind ->
                val color = value.colors[kind]
                if (color == null) remove(colorKey(kind)) else putInt(colorKey(kind), color)
            }
        }.commit()

    private fun colorKey(kind: HolidayKind) = "color-${kind.name}"
}

data class HolidayUiState(
    val year: Int = LocalDate.now().year,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val calendar: ProductionCalendar? = null,
    val downloadedAt: Long = 0,
    val error: String? = null,
    val holidays: List<Holiday> = emptyList(),
)

fun holidaysForCalendar(year: Int, calendar: ProductionCalendar?): List<Holiday> {
    val rules = ((year - 1)..(year + 1)).filter { it in 1900..2100 }.flatMap(HolidayRules::holidays)
    val publicDays = calendar?.days?.values?.filter { it.holiday != null }.orEmpty()
    return (rules.filterNot { it.kind == HolidayKind.PUBLIC && it.date.year == calendar?.year } + publicDays.map {
        Holiday("ru:production:${it.date}", it.date, requireNotNull(it.holiday), HolidayKind.PUBLIC)
    }).distinctBy { it.id }.sortedWith(compareBy<Holiday> { it.date }.thenBy { it.kind.ordinal }.thenBy { it.title })
}

class HolidayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HolidayRepository(application)
    private val preferences = HolidayPreferences(application)
    private val _settings = MutableStateFlow(preferences.read())
    val settings = _settings.asStateFlow()
    private val _state = MutableStateFlow(HolidayUiState())
    val state = _state.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _settingsError = MutableStateFlow(false)
    val settingsError = _settingsError.asStateFlow()
    private var job: Job? = null
    fun clearSettingsError() { _settingsError.value = false }

    fun saveSettings(value: HolidaySettings, onSaved: () -> Unit = {}) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { preferences.save(value) }) {
                    _settings.value = value
                    onSaved()
                } else _settingsError.value = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _settingsError.value = true }
            finally { _saving.value = false }
        }
    }

    fun loadYear(year: Int, force: Boolean = false) {
        if (year !in 1900..2100) return
        if (job?.isActive == true && _state.value.year == year) return
        job?.cancel()
        _state.value = if (_state.value.year == year) _state.value.copy(loading = true, error = null)
            else HolidayUiState(year = year, loading = true)
        job = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.load(year) }
                _state.value = HolidayUiState(year, true, false, loaded.calendar, loaded.downloadedAt, holidays = holidaysForCalendar(year, loaded.calendar))
                val refresh = force || (_settings.value.enabled && _settings.value.autoUpdate && withContext(Dispatchers.IO) { repository.shouldRefresh(year, loaded) })
                if (refresh) {
                    _state.value = _state.value.copy(loading = true)
                    try {
                        val updated = withContext(Dispatchers.IO) { repository.refresh(year) }
                        _state.value = HolidayUiState(year, true, false, updated.calendar, updated.downloadedAt, holidays = holidaysForCalendar(year, updated.calendar))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        _state.value = _state.value.copy(loading = false, error = "Обновить календарь не удалось. Сохранённые праздники доступны; попробуйте позже.")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _state.value = HolidayUiState(year = year, loaded = true, holidays = holidaysForCalendar(year, null), error = "Календарь переносов пока недоступен. Показан встроенный справочник праздников.")
            }
        }
    }
}
