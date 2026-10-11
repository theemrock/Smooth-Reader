package com.theemrock.smoothreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class PlaybackBookMetadata(
    val bookId: String,
    val title: String,
    val author: String?,
    val mode: ReadingMode
)

class ReaderPlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "com.theemrock.smoothreader.playback.PLAY"
        const val ACTION_PAUSE = "com.theemrock.smoothreader.playback.PAUSE"
        const val ACTION_STOP = "com.theemrock.smoothreader.playback.STOP"
        const val EXTRA_BOOK_ID = "reader_playback_book_id"
        const val EXTRA_READING_MODE = "reader_playback_reading_mode"

        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIFICATION_ID = 1010
        private const val REQUEST_CONTENT = 2010
        private const val REQUEST_PLAY = 2011
        private const val REQUEST_PAUSE = 2012
        private const val REQUEST_STOP = 2013

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, ReaderPlaybackService::class.java).setAction(action)
    }

    internal inner class LocalBinder : Binder() {
        val state: StateFlow<PlaybackSnapshot>
            get() = controller.state

        val stopping: StateFlow<Boolean>
            get() = stoppingState

        fun hasSession(bookId: String): Boolean = controller.hasSession(bookId)

        fun prepareSession(
            bookId: String,
            source: ReadingSource,
            initialPosition: ReadingPosition,
            title: String,
            author: String?,
            mode: ReadingMode
        ): Boolean {
            metadata = PlaybackBookMetadata(
                bookId,
                title.ifBlank { "Kitap" },
                author?.takeIf { it.isNotBlank() },
                mode
            )
            val prepared = controller.prepare(bookId, source, initialPosition)
            publish(controller.state.value)
            return prepared
        }

        fun updateMetadata(bookId: String, title: String, author: String?, mode: ReadingMode) {
            if (!controller.hasSession(bookId)) return
            metadata = PlaybackBookMetadata(
                bookId,
                title.ifBlank { "Kitap" },
                author?.takeIf { it.isNotBlank() },
                mode
            )
            publish(controller.state.value)
        }

        fun clear(reason: String) = controller.clear(reason)
        fun play(): Boolean = coordinator.play()
        fun pause(reason: String) = coordinator.pause(reason)
        fun stop(reason: String) = requestStop(reason)
        fun interruptForSeek(reason: String) = controller.interruptForSeek(reason)
        fun adoptLegacyPosition(position: ReadingPosition) = controller.adoptLegacyPosition(position)
        fun seek(position: ReadingPosition, restartIfPlaying: Boolean, reason: String) =
            controller.seek(position, restartIfPlaying, reason)

        fun setSpeed(speed: Int, reason: String) = controller.setSpeed(speed, reason)
        fun toggleSound(reason: String) = controller.toggleSound(reason)
    }

    private val binder = LocalBinder()
    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private lateinit var controller: ReaderPlaybackController
    private lateinit var coordinator: ReaderPlaybackCoordinator
    private lateinit var mediaSession: MediaSession
    private lateinit var audioFocus: AndroidPlaybackAudioFocus
    private lateinit var notificationManager: NotificationManager
    private val stoppingState = MutableStateFlow(false)
    private var metadata: PlaybackBookMetadata? = null
    private var foregroundStarted = false
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        mediaSession = createMediaSession()
        controller = ReaderPlaybackController(
            engine = TtsEngine(applicationContext),
            scope = serviceScope,
            persistPosition = { bookId, position, totalParagraphs ->
                ProgressStore(applicationContext).setProgress(
                    ReadingProgress(bookId, position, totalParagraphs)
                )
            },
            onPersistenceError = {
                Toast.makeText(
                    applicationContext,
                    "Okuma konumu kaydedilemedi. Önceki kayıt korundu.",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
        audioFocus = AndroidPlaybackAudioFocus()
        coordinator = ReaderPlaybackCoordinator(
            controller = controller,
            audioFocus = audioFocus,
            foreground = object : PlaybackForeground {
                override fun startForegroundPlayback() = ensureForeground()
                override fun stopForegroundPlayback() = removeForeground()
            },
            stopService = ::stopSelf
        )
        serviceScope.launch {
            controller.state.collect(::publish)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                ensureForeground()
                coordinator.play()
            }
            ACTION_PAUSE -> coordinator.pause("servicePause")
            ACTION_STOP -> requestStop("serviceStop")
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        requestStop("taskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        coordinator.pause("serviceDestroy")
        removeForeground()
        mediaSession.isActive = false
        mediaSession.release()
        controller.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun requestStop(reason: String) {
        if (stopping) return
        stopping = true
        stoppingState.value = true
        coordinator.stop(reason)
    }

    private fun publish(snapshot: PlaybackSnapshot) {
        coordinator.onPlaybackStateChanged(snapshot.isPlaying)
        publishMediaState(snapshot)
        if (foregroundStarted) notificationManager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun ensureForeground() {
        val notification = buildNotification(controller.state.value)
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        foregroundStarted = true
    }

    private fun removeForeground() {
        if (!foregroundStarted) return
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sesli okuma",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Smooth Reader sesli okuma kontrolleri"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(snapshot: PlaybackSnapshot): Notification {
        val isPlaying = snapshot.isPlaying
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                metadata?.let {
                    putExtra(EXTRA_BOOK_ID, it.bookId)
                    putExtra(EXTRA_READING_MODE, it.mode.name)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            if (isPlaying) REQUEST_PAUSE else REQUEST_PLAY,
            actionIntent(this, if (isPlaying) ACTION_PAUSE else ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            actionIntent(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = Notification.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Duraklat" else "Oynat",
            playPauseIntent
        ).build()
        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Durdur",
            stopIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader_notification)
            .setContentTitle(metadata?.title ?: "Smooth Reader")
            .setContentText(metadata?.author)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }

    private fun createMediaSession(): MediaSession =
        MediaSession(this, "SmoothReaderPlayback").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    ensureForeground()
                    coordinator.play()
                }

                override fun onPause() = coordinator.pause("mediaSessionPause")
                override fun onStop() = requestStop("mediaSessionStop")
            })
            isActive = true
        }

    private fun publishMediaState(snapshot: PlaybackSnapshot) {
        val playbackState = when {
            snapshot.bookId == null -> PlaybackState.STATE_NONE
            snapshot.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    playbackState,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (snapshot.isPlaying) 1f else 0f
                )
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, metadata?.title ?: "Smooth Reader")
                metadata?.author?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
            }.build()
        )
    }

    private inner class AndroidPlaybackAudioFocus : PlaybackAudioFocus {
        private val audioManager = getSystemService(AudioManager::class.java)
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    coordinator.onAudioFocusLost("audioFocus:$change")
                }
            }
            .build()

        override fun requestPlaybackFocus(): Boolean =
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        override fun abandonPlaybackFocus() {
            audioManager.abandonAudioFocusRequest(request)
        }
    }
}
