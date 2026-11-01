package com.peyvand.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import com.peyvand.app.presentation.MainViewModel
import com.peyvand.app.presentation.MainViewModelFactory
import com.peyvand.app.presentation.PeyvandApp
import com.peyvand.core.data.language.PreferencesLanguageRepository
import com.peyvand.core.domain.language.AppLanguage
import com.peyvand.core.domain.language.SelectLanguageUseCase
import com.peyvand.core.designsystem.theme.PeyvandTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()

        val languageRepository = PreferencesLanguageRepository(applicationContext)

        // Read the persisted preference asynchronously before exposing Compose UI.
        // On first launch, use Persian as the UI locale without marking it selected.
        lifecycleScope.launch {
            val selectedLanguage = languageRepository.getSelectedLanguage()
            val localeToApply = selectedLanguage?.localeTag ?: AppLanguage.PERSIAN.localeTag
                ?: error("The default Persian locale must be configured.")
            val requestedLocales = LocaleListCompat.forLanguageTags(localeToApply)
            val currentLocaleTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()

            if (currentLocaleTags != requestedLocales.toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(requestedLocales)
                return@launch
            }

            val factory = MainViewModelFactory(
                initialLanguage = selectedLanguage,
                selectLanguage = SelectLanguageUseCase(languageRepository),
            )
            val viewModel = ViewModelProvider(this@MainActivity, factory)[MainViewModel::class.java]

            setContent {
                PeyvandTheme {
                    PeyvandApp(viewModel = viewModel)
                }
            }
        }
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.White.toArgb()
        window.navigationBarColor = Color.White.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
