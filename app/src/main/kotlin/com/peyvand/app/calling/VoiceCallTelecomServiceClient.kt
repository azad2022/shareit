package com.peyvand.app.calling

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Application-facing command gateway. CallsManager itself is hosted only by the
 * phoneCall foreground service; this client never constructs Telecom APIs.
 */
class VoiceCallTelecomServiceClient(context: Context) {
    private val appContext = context.applicationContext
    private val mutableRegistered = MutableStateFlow(false)
    private val startRequested = AtomicBoolean(false)
    val isRegistered: StateFlow<Boolean> = mutableRegistered.asStateFlow()

    fun startForCall() {
        if (!startRequested.compareAndSet(false, true)) return
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, PeyvandVoiceCallService::class.java)
                    .setAction(PeyvandVoiceCallService.ACTION_START),
            )
        } catch (exception: RuntimeException) {
            startRequested.set(false)
            throw exception
        }
    }

    /** Stops only an idle Telecom service; an active call remains under service ownership. */
    fun requestStopIfIdle() {
        if (!startRequested.get()) return
        try {
            appContext.startService(
                Intent(appContext, PeyvandVoiceCallService::class.java)
                    .setAction(PeyvandVoiceCallService.ACTION_STOP_IF_IDLE),
            )
        } catch (_: RuntimeException) {
            // Service may already have stopped or Android may be tearing down the process.
        }
    }

    suspend fun awaitRegistered(timeoutMillis: Long = 5_000L) {
        withTimeout(timeoutMillis) { isRegistered.first { it } }
    }

    fun answerIncomingCall(callId: UUID) {
        appContext.startService(
            Intent(appContext, PeyvandVoiceCallService::class.java)
                .setAction(PeyvandVoiceCallService.ACTION_ANSWER)
                .putExtra(AndroidVoiceCallTelecomController.EXTRA_CALL_ID, callId.toString()),
        )
    }

    internal fun publishRegistered(registered: Boolean) {
        mutableRegistered.value = registered
    }

    internal fun onServiceStopped() {
        mutableRegistered.value = false
        startRequested.set(false)
    }
}
