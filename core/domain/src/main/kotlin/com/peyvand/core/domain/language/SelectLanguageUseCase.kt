package com.peyvand.core.domain.language

class SelectLanguageUseCase(
    private val repository: LanguageRepository,
) {
    suspend operator fun invoke(language: AppLanguage) {
        require(language.isSelectable && language.localeTag != null) {
            "The selected language is not ready for user-facing use."
        }
        repository.selectLanguage(language)
    }
}
