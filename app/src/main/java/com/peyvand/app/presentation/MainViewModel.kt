package com.peyvand.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peyvand.core.domain.language.AppLanguage
import com.peyvand.core.domain.language.SelectLanguageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    initialLanguage: AppLanguage?,
    private val selectLanguage: SelectLanguageUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(
        initialLanguage?.let(MainUiState::Home) ?: MainUiState.LanguageSelection(),
    )
    val uiState = _uiState.asStateFlow()

    fun confirmLanguageSelection(language: AppLanguage) {
        val currentState = _uiState.value as? MainUiState.LanguageSelection ?: return
        if (currentState.isSaving || !language.isSelectable) return

        viewModelScope.launch {
            _uiState.value = currentState.copy(isSaving = true, hasError = false)
            try {
                selectLanguage(language)
                _uiState.value = MainUiState.Home(language)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = MainUiState.LanguageSelection(
                    isSaving = false,
                    hasError = true,
                )
            }
        }
    }
}
