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
import com.peyvand.app.presentation.files.ShareSelectionViewModel
import com.peyvand.app.presentation.files.ShareSelectionViewModelFactory
import com.peyvand.app.presentation.StartupErrorScreen
import com.peyvand.app.presentation.StartupLoadingScreen
import com.peyvand.app.presentation.discovery.DeviceDiscoveryViewModel
import com.peyvand.app.presentation.discovery.DeviceDiscoveryViewModelFactory
import com.peyvand.app.presentation.network.NetworkEnvironmentViewModel
import com.peyvand.app.presentation.network.NetworkEnvironmentViewModelFactory
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
        data class Ready(
            val languageViewModel: MainViewModel,
            val discoveryViewModel: DeviceDiscoveryViewModel,
            val networkEnvironmentViewModel: NetworkEnvironmentViewModel,
            val shareSelectionViewModel: ShareSelectionViewModel,
        ) : StartupState
    }

    private var startupState by mutableStateOf<StartupState>(StartupState.Loading)

    private val discoveryRepository
        get() = (application as PeyvandApplication).deviceDiscoveryRepository

    private val networkEnvironmentRepository
        get() = (application as PeyvandApplication).networkEnvironmentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()

        setContent {
            PeyvandTheme {
                when (val state = startupState) {
                    StartupState.Loading -> StartupLoadingScreen()
                    StartupState.Failed -> StartupErrorScreen(
                        onRetry = ::initializeApplication,
                    )
                    is StartupState.Ready -> PeyvandApp(
                        languageViewModel = state.languageViewModel,
                        discoveryViewModel = state.discoveryViewModel,
                        networkEnvironmentViewModel = state.networkEnvironmentViewModel,
                        shareSelectionViewModel = state.shareSelectionViewModel,
                    )
                }
            }
        }

        initializeApplication()
    }

    override fun onStart() {
        super.onStart()
        discoveryRepository.startMonitoring()
        networkEnvironmentRepository.startMonitoring()
    }

    override fun onResume() {
        super.onResume()
        discoveryRepository.refreshAvailability()
        networkEnvironmentRepository.refresh()
    }

    override fun onStop() {
        discoveryRepository.stopMonitoring()
        networkEnvironmentRepository.stopMonitoring()
        super.onStop()
    }

    private fun initializeApplication() {
        startupState = StartupState.Loading
        lifecycleScope.launch {
            try {
                val languageRepository = PreferencesLanguageRepository(applicationContext)
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

                val languageViewModel = ViewModelProvider(
                    this@MainActivity,
                    MainViewModelFactory(
                        initialLanguage = selectedLanguage,
                        selectLanguage = SelectLanguageUseCase(languageRepository),
                    ),
                )[MainViewModel::class.java]

                val discoveryViewModel = ViewModelProvider(
                    this@MainActivity,
                    DeviceDiscoveryViewModelFactory(discoveryRepository),
                )[DeviceDiscoveryViewModel::class.java]

                val networkEnvironmentViewModel = ViewModelProvider(
                    this@MainActivity,
                    NetworkEnvironmentViewModelFactory(networkEnvironmentRepository),
                )[NetworkEnvironmentViewModel::class.java]

                val shareSelectionViewModel = ViewModelProvider(
                    this@MainActivity,
                    ShareSelectionViewModelFactory(
                        (application as PeyvandApplication).shareCatalogRepository,
                    ),
                )[ShareSelectionViewModel::class.java]

                startupState = StartupState.Ready(
                    languageViewModel = languageViewModel,
                    discoveryViewModel = discoveryViewModel,
                    networkEnvironmentViewModel = networkEnvironmentViewModel,
                    shareSelectionViewModel = shareSelectionViewModel,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                startupState = StartupState.Failed
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
