package dev.chungjungsoo.gptmobile.presentation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceBackgroundPlayback
import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoicePlaybackNotice
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
internal class VoicePlaybackService : Service() {
    @Inject lateinit var playback: VoiceBackgroundPlayback
    private lateinit var media: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var token: String? = null
    private var lastStart = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice playback", NotificationManager.IMPORTANCE_LOW)
        )
        media = MediaSession(this, "Voice playback")
        media.setCallback(object : MediaSession.Callback() {
            override fun onStop() = playback.stop(token)
            override fun onPause() = playback.stop(token)
            override fun onCustomAction(action: String, extras: Bundle?) {
                token?.let { current -> if (action == "$STOP/$current") playback.stop(current) }
            }
        })
        scope.launch {
            playback.notification.collect { if (it == null && lastStart != 0) stopSelfResult(lastStart) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val active = playback.notification.value
        // Always meet the foreground-start deadline, even if the reply finished before service creation.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(active), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: RuntimeException) {
            playback.stop(active?.token)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        token = active?.token
        if (intent?.action == STOP) playback.stop(intent.data?.lastPathSegment)
        if (playback.notification.value == null) {
            stopSelfResult(startId)
        } else {
            token?.let { media.setPlaybackState(playingState(it)) }
            media.isActive = true
        }
        return START_NOT_STICKY
    }

    private fun notification(active: VoicePlaybackNotice?): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Voice Mode")
            .setContentText("Playing a spoken reply")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        val style = Notification.MediaStyle().setMediaSession(media.sessionToken)
        if (active != null) {
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CHAT_ROOM_ID, active.roomId)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_EVENT_ID, active.token)
            builder.setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val stop = Intent(this, VoicePlaybackService::class.java).setAction(STOP).setData(Uri.parse("gptmobile://voice/stop/${active.token}"))
            builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, STOP_ICON), "Stop", PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE)).build())
            style.setShowActionsInCompactView(0)
        }
        return builder.setStyle(style).build()
    }

    override fun onDestroy() {
        scope.cancel()
        playback.stop(token)
        media.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "voice_playback"
        private const val NOTIFICATION_ID = 8054
        private const val STOP = "dev.chungjungsoo.gptmobile.voice.STOP"
        private const val STOP_ICON = android.R.drawable.ic_menu_close_clear_cancel

        // Modern system media controls read PlaybackState; older controls use Notification.Action.
        internal fun playingState(token: String): PlaybackState = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
            .addCustomAction("$STOP/$token", "Stop", STOP_ICON)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()

        fun start(context: Context, notice: VoicePlaybackNotice) {
            ContextCompat.startForegroundService(context, Intent(context, VoicePlaybackService::class.java).setData(Uri.parse("gptmobile://voice/play/${notice.token}")))
        }
    }
}
