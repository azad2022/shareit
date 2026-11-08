package com.peyvand.app.presentation.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.transfer.session.SingleFileTransferCoordinator
import com.peyvand.core.domain.files.ShareItem

class FileTransferViewModel(
    connectivityRepository: PeerConnectivityRepository,
    storageRepository: TransferFileStorageRepository,
) : ViewModel() {

    private val coordinator = SingleFileTransferCoordinator(
        scope = viewModelScope,
        connectivityRepository = connectivityRepository,
        storageRepository = storageRepository,
    )

    val state = coordinator.state

    fun prepareSession(isSender: Boolean, selectedItems: List<ShareItem>) =
        coordinator.prepareSession(isSender, selectedItems)

    fun startSending() = coordinator.startSending()

    fun acceptIncoming() = coordinator.acceptIncoming()

    fun rejectIncoming() = coordinator.rejectIncoming()

    fun acceptBatchOffer() = coordinator.acceptBatchOffer()

    fun toggleBatchConsentItem(itemId: String) = coordinator.toggleBatchConsentItem(itemId)

    fun acceptBatchIncoming() = coordinator.acceptBatchIncoming()

    fun cancelItem(itemId: String) = coordinator.cancelItem(itemId)

    fun cancelTransfer() = coordinator.cancelTransfer()

    fun leaveSession() = coordinator.leaveSession()

    override fun onCleared() {
        coordinator.close()
    }
}
