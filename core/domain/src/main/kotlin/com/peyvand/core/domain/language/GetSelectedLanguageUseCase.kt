package com.peyvand.core.domain.language

class GetSelectedLanguageUseCase(
    private val repository: LanguageRepository,
) {
    suspend operator fun invoke(): AppLanguage? = repository.getSelectedLanguage()
}
