package com.peyvand.app.calling

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallsManager
import com.peyvand.app.MainActivity
import com.peyvand.app.R
import com.peyvand.core.calling.VoiceCallOrchestrator
import com.peyvand.core.domain.call.VoiceCallDirection
import com.peyvand.core.domain.call.VoiceCallSnapshot
import com.peyvand.core.domain.call.VoiceCallStatus
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Service-owned bridge to Android Telecom. The controller must be constructed by
 * PeyvandVoiceCallService, not by Application or an Activity. Media and signalling remain
 * in their separate authenticated Wi-Fi Direct layers.
 */
class AndroidVoiceCallTelecomController(
    context: Context,
    private val scope: CoroutineScope,
    private val secureSession: StateFlow<SecureSessionSnapshot>,
    private val orchestrator: VoiceCallOrchestrator,
    private val onForegroundNotification: (Notification) -> Unit,
    private val onCallSessionFinished: () -> Unit,
) : Closeable {
    private val appContext = context
    private val callsManager = CallsManager(appContext)
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val mutableRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = mutableRegistered.asStateFlow()
    private val activeEntry = MutableStateFlow<ManagedCall?>(null)
    private val lock = Any()
    private var monitorJob: Job? = null
    private val closed = AtomicBoolean(false)

    init {
        createNotificationChannel()
        try {
            callsManager.registerAppWithTelecom(
                CallsManager.CAPABILITY_BASELINE,
                Build.VERSION_CODES.TIRAMISU,
            )
            mutableRegistered.value = true
        } catch (_: Exception) {
            mutableRegistered.value = false
        }
    }

    fun start() {
        check(!closed.get()) { "Voice call Telecom controller is closed." }
        check(monitorJob == null) { "Voice call Telecom controller already started." }
        monitorJob = scope.launch {
            combine(orchestrator.snapshot, secureSession) { call, peer -> call to peer }
                .collect { (call, peer) -> reconcile(call, peer) }
        }
    }

    /** Queues the system answer only after explicit in-app acceptance and RECORD_AUDIO grant. */
    suspend fun answerIncomingCall(callId: UUID) {
        val entry = withTimeout(CALL_ENTRY_TIMEOUT_MILLIS) {
            activeEntry.first { it?.callId == callId }
        } ?: throw IllegalStateException("Incoming call is not registered with Android Telecom.")
        check(entry.direction == VoiceCallDirection.INCOMING)
        entry.commands.send(CallCommand.Answer)
    }

    private fun reconcile(call: VoiceCallSnapshot, session: SecureSessionSnapshot) {
        if (closed.get()) return
        val shouldRegister = call.callId != null &&
            call.direction != null &&
            !call.isTerminal &&
            call.status != VoiceCallStatus.IDLE
        val current = activeEntry.value

        if (!shouldRegister) {
            if (current != null &&
                (call.callId != current.callId || call.isTerminal || call.status == VoiceCallStatus.IDLE)
            ) {
                current.commands.trySend(CallCommand.Disconnect)
            }
            return
        }

        val callId = call.callId ?: return
        val direction = call.direction ?: return
        if (current?.callId == callId) {
            updateNotification(current, call.status)
            return
        }
        if (!mutableRegistered.value) return

        val name = session.peerDisplayName
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.chat_conversation_title)
        val entry = ManagedCall(callId, direction, name)
        synchronized(lock) {
            activeEntry.value?.let { it.commands.trySend(CallCommand.Disconnect) }
            activeEntry.value = entry
        }
        entry.job = scope.launch { runManagedCall(entry) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runManagedCall(entry: ManagedCall) {
        try {
            val attributes = CallAttributesCompat(
                displayName = entry.peerName,
                address = Uri.parse("sip:peyvand-${entry.callId}"),
                direction = if (entry.direction == VoiceCallDirection.INCOMING) {
                    CallAttributesCompat.DIRECTION_INCOMING
                } else {
                    CallAttributesCompat.DIRECTION_OUTGOING
                },
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = 0,
                isLogExcluded = true,
            )
            callsManager.addCall(
                callAttributes = attributes,
                onAnswer = { requestedType ->
                    if (requestedType != CallAttributesCompat.CALL_TYPE_AUDIO_CALL) {
                        throw IllegalStateException("PEYVAND supports audio-only calls.")
                    }
                    openIncomingCallInApp(entry.callId)
                },
                onDisconnect = {
                    if (!entry.closing.get()) terminateFromTelecom(entry.callId)
                },
                onSetActive = {
                    val current = orchestrator.snapshot.value
                    if (current.callId != entry.callId ||
                        current.status !in setOf(VoiceCallStatus.ACTIVE, VoiceCallStatus.NEGOTIATING)
                    ) {
                        throw IllegalStateException("The PEYVAND call is not ready to become active.")
                    }
                    if (!orchestrator.setTelecomMediaActive(true)) {
                        throw IllegalStateException("PEYVAND could not resume its verified audio path.")
                    }
                },
                onSetInactive = {
                    if (!orchestrator.setTelecomMediaActive(false)) {
                        throw IllegalStateException("PEYVAND could not stop microphone capture and audio playout.")
                    }
                },
            ) {
                updateNotification(entry, orchestrator.snapshot.value.status)
                val muteJob = launch {
                    isMuted.collect { muted -> orchestrator.setMuted(muted) }
                }
                val activationJob = launch {
                    orchestrator.snapshot.collect { current ->
                        if (entry.direction == VoiceCallDirection.OUTGOING &&
                            current.callId == entry.callId &&
                            current.status == VoiceCallStatus.ACTIVE &&
                            entry.setActiveRequested.compareAndSet(false, true)
                        ) {
                            val result = setActive()
                            requireSuccess(result, "Telecom could not activate the PEYVAND call.")
                        }
                    }
                }
                launch {
                    try {
                        for (command in entry.commands) {
                            when (command) {
                                CallCommand.Answer -> {
                                    val result = answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                                    requireSuccess(result, "Telecom could not answer the PEYVAND call.")
                                }
                                CallCommand.Disconnect -> {
                                    entry.closing.set(true)
                                    val result = disconnect(DisconnectCause(DisconnectCause.LOCAL))
                                    requireSuccess(result, "Telecom could not disconnect the PEYVAND call.")
                                    break
                                }
                            }
                        }
                    } finally {
                        muteJob.cancel()
                        activationJob.cancel()
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            entry.closing.set(true)
            terminateFromTelecom(entry.callId)
        } finally {
            entry.commands.close()
            notificationManager.cancel(NOTIFICATION_ID)
            synchronized(lock) {
                if (activeEntry.value === entry) activeEntry.value = null
            }
            runCatching(onCallSessionFinished)
        }
    }

    private fun openIncomingCallInApp(callId: UUID) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_ANSWER_INCOMING
            putExtra(EXTRA_CALL_ID, callId.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        appContext.startActivity(intent)
    }

    private suspend fun terminateFromTelecom(callId: UUID) {
        val current = orchestrator.snapshot.value
        if (current.callId != callId || current.isTerminal) return
        when (current.status) {
            VoiceCallStatus.INCOMING_RINGING -> orchestrator.rejectIncomingCall(callId)
            VoiceCallStatus.OUTGOING_RINGING -> orchestrator.cancelOutgoingCall(callId)
            else -> orchestrator.hangUp(callId)
        }
    }

    private fun updateNotification(entry: ManagedCall, status: VoiceCallStatus) {
        val incoming = entry.direction == VoiceCallDirection.INCOMING &&
            status == VoiceCallStatus.INCOMING_RINGING
        val person = Person.Builder().setName(entry.peerName).build()
        val title = when {
            incoming -> R.string.voice_call_notification_incoming
            status == VoiceCallStatus.OUTGOING_RINGING -> R.string.voice_call_notification_outgoing
            else -> R.string.voice_call_notification_active
        }
        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(!incoming)
            .setContentIntent(activityPendingIntent(entry.callId))
            .setContentTitle(appContext.getString(title))
            .setContentText(entry.peerName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val style = if (incoming) {
            NotificationCompat.CallStyle.forIncomingCall(
                person,
                broadcastPendingIntent(entry.callId, ACTION_DECLINE),
                activityPendingIntent(entry.callId),
            )
        } else {
            NotificationCompat.CallStyle.forOngoingCall(
                person,
                broadcastPendingIntent(entry.callId, ACTION_HANG_UP),
            )
        }
        builder.setStyle(style)
        val notification = builder.build()
        if (canPostNotifications()) {
            runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
        }
        runCatching { onForegroundNotification(notification) }
    }

    private fun canPostNotifications(): Boolean =
        notificationManager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED)

    private fun activityPendingIntent(callId: UUID): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_ANSWER_INCOMING
            putExtra(EXTRA_CALL_ID, callId.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            appContext,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcastPendingIntent(callId: UUID, action: String): PendingIntent {
        val intent = Intent(appContext, VoiceCallNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, callId.toString())
        }
        return PendingIntent.getBroadcast(
            appContext,
            callId.hashCode() xor action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.voice_call_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun requireSuccess(result: CallControlResult, message: String) {
        if (result !is CallControlResult.Success) throw IllegalStateException(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        monitorJob?.cancel()
        activeEntry.value?.let { entry ->
            entry.closing.set(true)
            entry.commands.trySend(CallCommand.Disconnect)
        }
        notificationManager.cancel(NOTIFICATION_ID)
        mutableRegistered.value = false
    }

    private class ManagedCall(
        val callId: UUID,
        val direction: VoiceCallDirection,
        val peerName: String,
    ) {
        val commands = Channel<CallCommand>(capacity = 8)
        val closing = AtomicBoolean(false)
        val setActiveRequested = AtomicBoolean(false)
        var job: Job? = null
    }

    private sealed interface CallCommand {
        data object Answer : CallCommand
        data object Disconnect : CallCommand
    }

    companion object {
        const val ACTION_ANSWER_INCOMING = "com.peyvand.app.action.ANSWER_INCOMING_VOICE_CALL"
        const val ACTION_DECLINE = "com.peyvand.app.action.DECLINE_VOICE_CALL"
        const val ACTION_HANG_UP = "com.peyvand.app.action.HANG_UP_VOICE_CALL"
        const val EXTRA_CALL_ID = "com.peyvand.app.extra.VOICE_CALL_ID"
        const val NOTIFICATION_ID = 64004
        const val NOTIFICATION_CHANNEL_ID = "peyvand_voice_calls"
        private const val CALL_ENTRY_TIMEOUT_MILLIS = 5_000L
    }
}
