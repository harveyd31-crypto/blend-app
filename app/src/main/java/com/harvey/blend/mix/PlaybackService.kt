package com.harvey.blend.mix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.harvey.blend.MainActivity
import com.harvey.blend.R
import com.harvey.blend.repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps the music playing with the screen off and shows a notification with controls. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, build("Blend", "Getting ready…", true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        val repo = applicationContext.repo
        scope.launch {
            combine(repo.mix, repo.tracks) { m, t -> Triple(m.masterId?.let { t[it] }, m.nextId?.let { t[it] }, m.playing) }
                .distinctUntilChanged()
                .collect { (cur, next, playing) ->
                    val title = cur?.title ?: "Blend"
                    val text = listOfNotNull(cur?.artist?.takeIf { it.isNotBlank() }, next?.let { "next: ${it.title}" }).joinToString(" · ")
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, text, playing))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = applicationContext.repo
        when (intent?.action) {
            ACTION_TOGGLE -> repo.togglePlay()
            ACTION_NEXT -> repo.mixNow()
            ACTION_STOP -> { repo.stop(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, text: String, playing: Boolean): Notification {
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, PlaybackService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_blend_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, if (playing) "Pause" else "Play", action(ACTION_TOGGLE, 1))
            .addAction(0, "Mix now", action(ACTION_NEXT, 2))
            .addAction(0, "Stop", action(ACTION_STOP, 3))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 31
        const val ACTION_TOGGLE = "com.harvey.blend.TOGGLE"
        const val ACTION_NEXT = "com.harvey.blend.MIX_NOW"
        const val ACTION_STOP = "com.harvey.blend.STOP"
    }
}
