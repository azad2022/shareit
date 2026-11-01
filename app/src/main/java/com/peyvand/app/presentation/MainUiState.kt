package com.peyvand.app.presentation

import com.peyvand.core.domain.language.AppLanguage

sealed interface MainUiState {
    data class LanguageSelection(
        val isSaving: Boolean = false,
        val hasError: Boolean = false,
    ) : MainUiState

    data class Home(
        val selectedLanguage: AppLanguage,
    ) : MainUiState
}
