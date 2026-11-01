package com.peyvand.core.data.language

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.peyvand.core.domain.language.AppLanguage
import com.peyvand.core.domain.language.LanguageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.peyvandSettingsDataStore by preferencesDataStore(
    name = "peyvand_settings",
)

private val selectedLanguageKey = stringPreferencesKey("selected_language_tag")

/**
 * Persists the chosen language asynchronously and delegates Android locale
 * application to AppCompat so API 26+ uses the supported per-app locale path.
 * The Activity hosting Compose must be an AppCompatActivity.
 */
class PreferencesLanguageRepository(
    context: Context,
) : LanguageRepository {
    private val dataStore = context.applicationContext.peyvandSettingsDataStore

    override suspend fun getSelectedLanguage(): AppLanguage? {
        val savedTag = dataStore.data.first()[selectedLanguageKey]
        return AppLanguage.fromLocaleTag(savedTag)
    }

    override suspend fun selectLanguage(language: AppLanguage) {
        val localeTag = language.localeTag
        require(language.isSelectable && localeTag != null) {
            "The selected language is not ready for user-facing use."
        }

        dataStore.edit { preferences ->
            preferences[selectedLanguageKey] = localeTag
        }

        withContext(Dispatchers.Main.immediate) {
            val requestedLocales = LocaleListCompat.forLanguageTags(localeTag)
            val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (currentTags != requestedLocales.toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(requestedLocales)
            }
        }
    }
}
