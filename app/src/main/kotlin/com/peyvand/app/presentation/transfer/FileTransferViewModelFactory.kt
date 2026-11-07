package com.peyvand.app.presentation.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.domain.transfer.TransferFileStorageRepository

class FileTransferViewModelFactory(
    private val connectivityRepository: PeerConnectivityRepository,
    private val storageRepository: TransferFileStorageRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(FileTransferViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel type: " + modelClass.name)
        }
        return FileTransferViewModel(
            connectivityRepository = connectivityRepository,
            storageRepository = storageRepository,
        ) as T
    }
}
