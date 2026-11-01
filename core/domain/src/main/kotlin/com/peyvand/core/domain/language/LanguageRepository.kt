package com.peyvand.core.domain.language

interface LanguageRepository {
    suspend fun getSelectedLanguage(): AppLanguage?

    suspend fun selectLanguage(language: AppLanguage)
}
