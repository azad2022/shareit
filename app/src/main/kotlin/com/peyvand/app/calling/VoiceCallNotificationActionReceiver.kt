package com.peyvand.app.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.peyvand.app.PeyvandApplication
import com.peyvand.core.domain.call.VoiceCallStatus
import java.util.UUID
import kotlinx.coroutines.launch

class VoiceCallNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(AndroidVoiceCallTelecomController.EXTRA_CALL_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val app = context.applicationContext as? PeyvandApplication ?: return
        val pendingResult = goAsync()

        app.applicationScope.launch {
            try {
                val snapshot = app.voiceCallOrchestrator.snapshot.value
                if (snapshot.callId != callId || snapshot.isTerminal) return@launch
                when (intent.action) {
                    AndroidVoiceCallTelecomController.ACTION_DECLINE -> {
                        if (snapshot.status == VoiceCallStatus.INCOMING_RINGING) {
                            app.voiceCallOrchestrator.rejectIncomingCall(callId)
                        }
                    }
                    AndroidVoiceCallTelecomController.ACTION_HANG_UP -> {
                        app.voiceCallOrchestrator.hangUp(callId)
                    }
                }
            } catch (_: Exception) {
                // State-machine state is authoritative; stale notification actions are no-ops.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
