package com.peyvand.app.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.peyvand.app.PeyvandApplication
import com.peyvand.app.R
import com.peyvand.core.domain.call.VoiceCallStatus
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns Android CallsManager for the duration of a call. This service starts only in
 * response to an explicit outgoing-call action or a live authenticated incoming invite.
 */
class PeyvandVoiceCallService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telecomController: AndroidVoiceCallTelecomController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as PeyvandApplication

        // A phoneCall foreground service must be promoted immediately. The controller
        // replaces this short-lived preparation notification with CallStyle from the
        // Core-Telecom CallControlScope as soon as addCall succeeds.
        startForegroundCallNotification(createStartingNotification())

        val controller = AndroidVoiceCallTelecomController(
            context = this,
            scope = serviceScope,
            secureSession = app.peerConnectionRepository.secureSession,
            orchestrator = app.voiceCallOrchestrator,
            onForegroundNotification = ::startForegroundCallNotification,
            onCallSessionFinished = {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        )
        telecomController = controller
        app.voiceCallTelecomServiceClient.publishRegistered(controller.isRegistered.value)
        serviceScope.launch {
            controller.isRegistered.collect(app.voiceCallTelecomServiceClient::publishRegistered)
        }
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> {
                val callId = intent.getStringExtra(AndroidVoiceCallTelecomController.EXTRA_CALL_ID)
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (callId != null) {
                    serviceScope.launch {
                        runCatching { telecomController?.answerIncomingCall(callId) }
                    }
                }
            }
            ACTION_STOP_IF_IDLE -> serviceScope.launch { stopIfIdle(startId) }
            ACTION_START,
            null,
            -> Unit
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle(startId: Int) {
        val app = application as PeyvandApplication
        val status = app.voiceCallOrchestrator.snapshot.value.status
        val terminalOrIdle = status in setOf(
            VoiceCallStatus.IDLE,
            VoiceCallStatus.ENDED,
            VoiceCallStatus.FAILED,
        )
        if (!terminalOrIdle || telecomController?.hasManagedCall == true) return

        telecomController?.close()
        telecomController = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun startForegroundCallNotification(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AndroidVoiceCallTelecomController.NOTIFICATION_ID,
            notification,
            serviceType,
        )
    }

    private fun createStartingNotification(): Notification =
        NotificationCompat.Builder(this, AndroidVoiceCallTelecomController.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.voice_call_notification_starting))
            .setContentText(getString(R.string.voice_call_notification_starting_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    AndroidVoiceCallTelecomController.NOTIFICATION_CHANNEL_ID,
                    getString(R.string.voice_call_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        telecomController?.close()
        telecomController = null
        (application as? PeyvandApplication)?.voiceCallTelecomServiceClient
            ?.onServiceStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.peyvand.app.action.START_VOICE_CALL_SERVICE"
        const val ACTION_ANSWER = "com.peyvand.app.action.ANSWER_VOICE_CALL"
        const val ACTION_STOP_IF_IDLE = "com.peyvand.app.action.STOP_IDLE_VOICE_CALL_SERVICE"
    }
}
