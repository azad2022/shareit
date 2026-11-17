package com.peyvand.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peyvand.core.domain.profile.DeviceProfileRepository
import com.peyvand.core.domain.profile.LocalDeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class DeviceProfileUiError {
    IMAGE_IMPORT_FAILED,
    IMAGE_INVALID,
    INPUT_INVALID,
    SAVE_FAILED,
}

data class DeviceProfileUiState(
    val localProfile: LocalDeviceProfile = LocalDeviceProfile(),
    val defaultDeviceName: String,
    val deviceNameDraft: String = "",
    val profileNameDraft: String = "",
    val avatarDraft: ByteArray? = null,
    val shareWithTrustedPeersDraft: Boolean = true,
    val isLoading: Boolean = true,
    val isImportingAvatar: Boolean = false,
    val isSaving: Boolean = false,
    val error: DeviceProfileUiError? = null,
) {
    val hasChanges: Boolean
        get() = deviceNameDraft != localProfile.deviceNameOverride.orEmpty() ||
            profileNameDraft != localProfile.profileName.orEmpty() ||
            !sameBytes(avatarDraft, localProfile.avatarBytes) ||
            shareWithTrustedPeersDraft != localProfile.shareWithTrustedPeers

    private fun sameBytes(first: ByteArray?, second: ByteArray?): Boolean =
        when {
            first == null -> second == null
            second == null -> false
            else -> first.contentEquals(second)
        }
}

/** Owns profile editing state only; it has no transport, Room, or image-decoding responsibilities. */
class DeviceProfileViewModel(
    private val repository: DeviceProfileRepository,
    defaultDeviceName: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        DeviceProfileUiState(defaultDeviceName = defaultDeviceName),
    )
    val state: StateFlow<DeviceProfileUiState> = mutableState.asStateFlow()
    private var avatarImportJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeLocalProfile().collect { profile ->
                val current = mutableState.value
                if (current.isLoading || !current.hasChanges) {
                    mutableState.value = current.withProfile(profile)
                } else {
                    mutableState.value = current.copy(localProfile = profile)
                }
            }
        }
    }

    fun onDeviceNameChanged(value: String) {
        mutableState.value = mutableState.value.copy(deviceNameDraft = value, error = null)
    }

    fun onProfileNameChanged(value: String) {
        mutableState.value = mutableState.value.copy(profileNameDraft = value, error = null)
    }

    fun onShareWithTrustedPeersChanged(checked: Boolean) {
        mutableState.value = mutableState.value.copy(
            shareWithTrustedPeersDraft = checked,
            error = null,
        )
    }

    fun clearAvatar() {
        mutableState.value = mutableState.value.copy(avatarDraft = null, error = null)
    }

    fun importAvatar(contentUri: String) {
        if (mutableState.value.isImportingAvatar || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(
            isImportingAvatar = true,
            error = null,
        )
        avatarImportJob = viewModelScope.launch {
            try {
                val encoded = repository.importAvatar(contentUri)
                mutableState.value = mutableState.value.copy(
                    avatarDraft = encoded,
                    isImportingAvatar = false,
                    error = null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(
                    isImportingAvatar = false,
                    error = DeviceProfileUiError.IMAGE_INVALID,
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isImportingAvatar = false,
                    error = DeviceProfileUiError.IMAGE_IMPORT_FAILED,
                )
            } finally {
                avatarImportJob = null
                if (mutableState.value.isImportingAvatar) {
                    mutableState.value = mutableState.value.copy(isImportingAvatar = false)
                }
            }
        }
    }

    fun discardChanges() {
        avatarImportJob?.cancel()
        avatarImportJob = null
        mutableState.value = mutableState.value.withProfile(mutableState.value.localProfile)
    }

    fun save() {
        val current = mutableState.value
        if (current.isLoading || current.isSaving || current.isImportingAvatar || !current.hasChanges) {
            return
        }
        mutableState.value = current.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                val saved = repository.saveLocalProfile(
                    deviceNameOverride = current.deviceNameDraft,
                    profileName = current.profileNameDraft,
                    avatarBytes = current.avatarDraft,
                    shareWithTrustedPeers = current.shareWithTrustedPeersDraft,
                )
                mutableState.value = mutableState.value.withProfile(saved)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    error = DeviceProfileUiError.INPUT_INVALID,
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    error = DeviceProfileUiError.SAVE_FAILED,
                )
            }
        }
    }

    private fun DeviceProfileUiState.withProfile(profile: LocalDeviceProfile) = copy(
        localProfile = profile,
        deviceNameDraft = profile.deviceNameOverride.orEmpty(),
        profileNameDraft = profile.profileName.orEmpty(),
        avatarDraft = profile.avatarBytes?.copyOf(),
        shareWithTrustedPeersDraft = profile.shareWithTrustedPeers,
        isLoading = false,
        isSaving = false,
        isImportingAvatar = false,
        error = null,
    )
}
