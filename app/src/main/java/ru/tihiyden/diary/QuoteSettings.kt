package ru.tihiyden.diary

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class QuotePreferences(context: Context, name: String = "quotes") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun read(): QuoteMode = QuoteMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: QuoteMode.DAILY
    fun save(mode: QuoteMode): Boolean = preferences.edit().putString("mode", mode.name).commit()
}

internal class QuoteViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = QuotePreferences(application)
    private val _mode = MutableStateFlow(preferences.read())
    val mode = _mode.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error = _error.asStateFlow()

    fun save(mode: QuoteMode) {
        if (_saving.value) return
        _saving.value = true
        _error.value = false
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { preferences.save(mode) }) _mode.value = mode
                else _error.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value = true
            } finally { _saving.value = false }
        }
    }
}
