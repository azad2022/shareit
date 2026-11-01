package com.peyvand.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.peyvand.app.presentation.MainViewModel
import com.peyvand.app.presentation.MainViewModelFactory
import com.peyvand.app.presentation.PeyvandApp
import com.peyvand.app.presentation.StartupErrorScreen
import com.peyvand.app.presentation.StartupLoadingScreen
import com.peyvand.core.data.language.PreferencesLanguageRepository
import com.peyvand.core.domain.language.AppLanguage
import com.peyvand.core.domain.language.SelectLanguageUseCase
import com.peyvand.core.designsystem.theme.PeyvandTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private sealed interface StartupState {
        data object Loading : StartupState
        data object Failed : StartupState
        data class Ready(val viewModel: MainViewModel) : StartupState
    }

    private var startupState by mutableStateOf<StartupState>(StartupState.Loading)
    private lateinit var languageRepository: PreferencesLanguageRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        languageRepository = PreferencesLanguageRepository(applicationContext)

        setContent {
            PeyvandTheme {
                when (val state = startupState) {
                    StartupState.Loading -> StartupLoadingScreen()
                    StartupState.Failed -> StartupErrorScreen(
                        onRetry = ::initializeApplication,
                    )
                    is StartupState.Ready -> PeyvandApp(viewModel = state.viewModel)
                }
            }
        }

        initializeApplication()
    }

    private fun initializeApplication() {
        startupState = StartupState.Loading
        lifecycleScope.launch {
            try {
                // Read the persisted choice before exposing the actual app UI.
                // Persian is only the initial UI locale until the user confirms it.
                val selectedLanguage = languageRepository.getSelectedLanguage()
                val localeToApply = selectedLanguage?.localeTag
                    ?: AppLanguage.PERSIAN.localeTag
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
                val viewModel = ViewModelProvider(
                    this@MainActivity,
                    factory,
                )[MainViewModel::class.java]

                startupState = StartupState.Ready(viewModel)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep the Activity usable and let the user retry a transient
                // settings-storage or locale initialization failure.
                startupState = StartupState.Failed
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // These fallback colors apply on API 26–34. Newer Android versions
        // enforce edge-to-edge and draw the app surface behind system bars.
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
