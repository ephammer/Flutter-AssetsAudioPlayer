package com.github.florent37.assets_audio_player

import StopWhenCall
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Message
import com.github.florent37.assets_audio_player.notification.AudioMetas
import com.github.florent37.assets_audio_player.notification.NotificationManager
import com.github.florent37.assets_audio_player.notification.NotificationService
import com.github.florent37.assets_audio_player.notification.NotificationSettings
import com.github.florent37.assets_audio_player.playerimplem.DurationMS
import com.github.florent37.assets_audio_player.playerimplem.PlayerImplem
import com.github.florent37.assets_audio_player.playerimplem.PlayerImplemExoPlayer
import com.github.florent37.assets_audio_player.playerimplem.PlayerImplemMediaPlayer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Does not depend on Flutter, feel free to use it in all your projects
 */
class Player(
        val id: String,
        private val context: Context,
        private val stopWhenCall: StopWhenCall,
        private val notificationManager: NotificationManager,
        private val flutterAssets: FlutterPlugin.FlutterAssets
) {

    companion object {
        const val VOLUME_WHEN_REDUCED = 0.3

        const val AUDIO_TYPE_NETWORK = "network"
        const val AUDIO_TYPE_LIVESTREAM = "liveStream"
        const val AUDIO_TYPE_FILE = "file"
        const val AUDIO_TYPE_ASSET = "asset"
    }

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // To handle position updates.
    private val handler = Handler()

    private var mediaPlayer: PlayerImplem? = null

    //region outputs
    var onVolumeChanged: ((Double) -> Unit)? = null
    var onPlaySpeedChanged: ((Double) -> Unit)? = null
    var onForwardRewind: ((Double) -> Unit)? = null
    var onReadyToPlay: ((DurationMS) -> Unit)? = null
    var onPositionChanged: ((Long) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onPlaying: ((Boolean) -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onNotificationPlayOrPause: (() -> Unit)? = null
    var onNotificationStop: (() -> Unit)? = null
    //endregion

    private var respectSilentMode: Boolean = false
    private var volume: Double = 1.0
    private var playSpeed: Double = 1.0

    private var isEnabledToPlayPause: Boolean = true
    private var isEnabledToChangeVolume: Boolean = true

    val isPlaying: Boolean
        get() = mediaPlayer != null && mediaPlayer!!.isPlaying

    private var lastRingerMode: Int? = null //see https://developer.android.com/reference/android/media/AudioManager.html?hl=fr#getRingerMode()

    private var displayNotification = false

    private var _playingPath : String? = null
    private var _durationMs : DurationMS = 0
    private var _positionMs : DurationMS = 0
    private var _lastOpenedPath : String? = null
    private var audioMetas: AudioMetas? = null
    private var notificationSettings: NotificationSettings? = null

    private val updatePosition = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mediaPlayer ->
                try {
                    if (!mediaPlayer.isPlaying) {
                        handler.removeCallbacks(this)
                    }

                    val positionMs : Long = mediaPlayer.currentPositionMs
                    val position = positionMs / 1000L

                    // Send position (seconds) to the application.
                    onPositionChanged?.invoke(position)

                    if (respectSilentMode) {
                        val ringerMode = am.ringerMode
                        if (lastRingerMode != ringerMode) { //if changed
                            lastRingerMode = ringerMode
                            setVolume(volume) //re-apply volume if changed
                        }
                    }

                    _positionMs = if(_durationMs != 0L) {
                        min(positionMs, _durationMs)
                    } else {
                        positionMs
                    }
                    updateNotifPosition()

                    // Update every 300ms.
                    handler.postDelayed(this, 300)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun next() {
        this.onNext?.invoke()
    }

    fun prev() {
        this.onPrev?.invoke()
    }

    fun onAudioUpdated(path: String, audioMetas: AudioMetas) {
        if(_playingPath == path || (_playingPath == null && _lastOpenedPath == path)){
            this.audioMetas = audioMetas
            updateNotif()
        }
    }

    fun open(assetAudioPath: String?,
             assetAudioPackage: String?,
             audioType: String,
             autoStart: Boolean,
             volume: Double,
             seek: Int?,
             respectSilentMode: Boolean,
             displayNotification: Boolean,
             notificationSettings: NotificationSettings,
             audioMetas: AudioMetas,
             playSpeed: Double,
             networkHeaders: Map<*, *>?,
             result: MethodChannel.Result,
             context: Context
    ) {
        try {
            stop(pingListener = false)
        } catch (t: Throwable){
            print(t)
        }

        this.displayNotification = displayNotification
        this.audioMetas = audioMetas
        this.notificationSettings = notificationSettings
        this.respectSilentMode = respectSilentMode

        _lastOpenedPath = assetAudioPath

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val durationMs = try {
                    openExoPlayer(
                            assetAudioPath = assetAudioPath,
                            assetAudioPackage = assetAudioPackage,
                            audioType = audioType,
                            networkHeaders= networkHeaders,
                            context = context
                    )
                } catch (t: Throwable) {
                    //fallback to mediaPlayer if error while opening
                    openMediaPlayer(
                            assetAudioPath = assetAudioPath,
                            assetAudioPackage = assetAudioPackage,
                            audioType = audioType,
                            networkHeaders= networkHeaders,
                            context = context
                    )
                }

                //here one open succeed
                onReadyToPlay?.invoke(durationMs)

                _playingPath = assetAudioPath
                _durationMs = durationMs

                setVolume(volume)
                setPlaySpeed(playSpeed)

                seek?.let {
                    this@Player.seek(milliseconds = seek * 1L)
                }

                if (autoStart) {
                    play() //display notif inside
                } else {
                    updateNotif() //if pause, we need to display the notif
                }
                result.success(null)
            } catch (t: Throwable) {
                //if one error while opening, result.error
                onPositionChanged?.invoke(0)
                t.printStackTrace()
                result.error("OPEN", t.message, null)
            }
        }
    }

    private suspend fun openExoPlayer(assetAudioPath: String?,
                                      assetAudioPackage: String?,
                                      audioType: String,
                                      networkHeaders: Map<*, *>?,
                                      context: Context
    ): DurationMS {
        mediaPlayer = PlayerImplemExoPlayer(
                onFinished = {
                    onFinished?.invoke()
                    //stop(pingListener = false)
                },
                onBuffering = {
                    onBuffering?.invoke(it)
                },
                onError = { t ->
                    //TODO, handle errors after opened
                }
        )

        try {
            return mediaPlayer!!.open(
                    context = context,
                    assetAudioPath = assetAudioPath,
                    audioType = audioType,
                    assetAudioPackage = assetAudioPackage,
                    networkHeaders= networkHeaders,
                    flutterAssets = flutterAssets
            )
        } catch (t: Throwable) {
            mediaPlayer?.release()
            throw  t
        }
    }

    private suspend fun openMediaPlayer(
            assetAudioPath: String?,
            assetAudioPackage: String?,
            audioType: String,
            networkHeaders: Map<*, *>?,
            context: Context
    ): DurationMS {
        mediaPlayer = PlayerImplemMediaPlayer(
                onFinished = {
                    onFinished?.invoke()
                    //stop(pingListener = false)
                },
                onBuffering = {
                    onBuffering?.invoke(it)
                },
                onError = { t ->
                    //TODO, handle errors after opened
                }
        )
        try {
            return mediaPlayer!!.open(
                    context = context,
                    assetAudioPath = assetAudioPath,
                    audioType = audioType,
                    assetAudioPackage = assetAudioPackage,
                    networkHeaders= networkHeaders,
                    flutterAssets = flutterAssets
            )
        } catch (t: Throwable) {
            mediaPlayer?.release()
            throw  t
        }
    }

    fun stop(pingListener: Boolean = true, removeNotification: Boolean = true) {
        mediaPlayer?.apply {
            // Reset duration and position.
            // handler.removeCallbacks(updatePosition);
            // channel.invokeMethod("player.duration", 0);
            onPositionChanged?.invoke(0)

            mediaPlayer?.stop()
            mediaPlayer?.release()
            onPlaying?.invoke(false)
            handler.removeCallbacks(updatePosition)
        }
        if (forwardHandler != null) {
            forwardHandler!!.stop()
            forwardHandler = null
        }
        mediaPlayer = null
        onForwardRewind?.invoke(0.0)
        if (pingListener) { //action from user
            onStop?.invoke()
            updateNotif(removeNotificationOnStop= removeNotification)
        }
    }


    fun toggle() {
        if (isPlaying) {
            pause()
        } else {
            play()
        }
    }

    private fun stopForward() {
        forwardHandler?.takeIf { h -> h.isActive }?.let { h ->
            h.stop()
            setPlaySpeed(this.playSpeed)
        }
        onForwardRewind?.invoke(0.0)
    }

    private fun updateNotifPosition() {
        this.audioMetas
                ?.takeIf { this.displayNotification }
                ?.takeIf { notificationSettings?.seekBarEnabled ?: true }
                ?.let { audioMetas ->
                    NotificationService.updatePosition(
                            context = context,
                            isPlaying = isPlaying,
                            speed = this.playSpeed.toFloat(),
                            currentPositionMs = _positionMs
                    )
        }
    }

    fun forceNotificationForGroup(
            audioMetas: AudioMetas,
            isPlaying: Boolean,
            display: Boolean,
            notificationSettings: NotificationSettings
    ) {
        notificationManager.showNotification(
                playerId = id,
                audioMetas = audioMetas,
                isPlaying = isPlaying,
                notificationSettings = notificationSettings,
                stop = !display,
                durationMs = 0
        )
    }

    fun showNotification(show: Boolean){
        val oldValue = this.displayNotification
        this.displayNotification = show
        if(oldValue) { //if was showing a notification
            notificationManager.stopNotification()
            //hide it
        } else {
            updateNotif()
        }
    }
    
    private fun updateNotif(removeNotificationOnStop: Boolean = true) {
        this.audioMetas?.takeIf { this.displayNotification }?.let { audioMetas ->
            this.notificationSettings?.let { notificationSettings ->
                updateNotifPosition()
                notificationManager.showNotification(
                        playerId = id,
                        audioMetas = audioMetas,
                        isPlaying = this.isPlaying,
                        notificationSettings = notificationSettings,
                        stop = removeNotificationOnStop && (mediaPlayer == null),
                        durationMs = this._durationMs
                )
            }
        }
    }

    fun play() {
        val audioState = this.stopWhenCall.requestAudioFocus()
        if (audioState == StopWhenCall.AudioState.AUTHORIZED_TO_PLAY) {
            this.isEnabledToPlayPause = true //this one must be called before play/pause()
            this.isEnabledToChangeVolume = true //this one must be called before play/pause()
            playerPlay()
        } //else will wait until focus is enabled
    }

    private fun playerPlay() { //the play
        if (isEnabledToPlayPause) { //can be disabled while recieving phone call
            mediaPlayer?.let { player ->
                stopForward()
                player.play()
                handler.post(updatePosition)
                onPlaying?.invoke(true)
                updateNotif()
            }
        } else {
            this.stopWhenCall.requestAudioFocus()
        }
    }

    fun pause() {
        if (isEnabledToPlayPause) {
            mediaPlayer?.let {
                it.pause()
                handler.removeCallbacks(updatePosition)

                stopForward()
                onPlaying?.invoke(false)
                updateNotif()
            }
        }
    }

    fun loopSingleAudio(loop: Boolean){
        mediaPlayer?.loopSingleAudio = loop
    }

    fun seek(milliseconds: Long) {
        mediaPlayer?.apply {
            val to = max(milliseconds, 0L)
            seekTo(to)
            onPositionChanged?.invoke(currentPositionMs / 1000L)
        }
    }

    fun seekBy(milliseconds: Long) {
        mediaPlayer?.let {
            val to = it.currentPositionMs + milliseconds;
            seek(to)
        }
    }

    fun setVolume(volume: Double) {
        if (isEnabledToChangeVolume) {
            this.volume = volume
            mediaPlayer?.let {
                var v = volume
                if (this.respectSilentMode) {
                    v = when (am.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE -> 0.toDouble()
                        else -> volume //AudioManager.RINGER_MODE_NORMAL
                    }
                }

                it.setVolume(v.toFloat())

                onVolumeChanged?.invoke(this.volume) //only notify the setted volume, not the silent mode one
            }
        }
    }

    private var forwardHandler: ForwardHandler? = null;

    fun setPlaySpeed(playSpeed: Double) {
        if (playSpeed >= 0) { //android only take positive play speed
            if (forwardHandler != null) {
                forwardHandler!!.stop()
                forwardHandler = null
            }
            this.playSpeed = playSpeed
            mediaPlayer?.let {
                it.setPlaySpeed(playSpeed.toFloat())
                onPlaySpeedChanged?.invoke(this.playSpeed)
            }
        }
    }

    fun forwardRewind(speed: Double) {
        if (forwardHandler == null) {
            forwardHandler = ForwardHandler()
        }

        mediaPlayer?.let {
            it.pause()
            //handler.removeCallbacks(updatePosition)
            //onPlaying?.invoke(false)
        }

        onForwardRewind?.invoke(speed)
        forwardHandler!!.start(this, speed)
    }

    private var volumeBeforePhoneStateChanged: Double? = null
    private var wasPlayingBeforeEnablePlayChange: Boolean? = null
    fun updateEnableToPlay(audioState: StopWhenCall.AudioState) {
        when (audioState) {
            StopWhenCall.AudioState.AUTHORIZED_TO_PLAY -> {
                this.isEnabledToPlayPause = true //this one must be called before play/pause()
                this.isEnabledToChangeVolume = true //this one must be called before play/pause()
                wasPlayingBeforeEnablePlayChange?.let {
                    //phone call ended
                    if (it) {
                        playerPlay()
                    } else {
                        pause()
                    }
                }
                volumeBeforePhoneStateChanged?.let {
                    setVolume(it)
                }
                wasPlayingBeforeEnablePlayChange = null
                volumeBeforePhoneStateChanged = null
            }
            StopWhenCall.AudioState.REDUCE_VOLUME -> {
                volumeBeforePhoneStateChanged = this.volume
                setVolume(VOLUME_WHEN_REDUCED)
                this.isEnabledToChangeVolume = false //this one must be called after setVolume()
            }
            StopWhenCall.AudioState.FORBIDDEN -> {
                wasPlayingBeforeEnablePlayChange = this.isPlaying
                pause()
                this.isEnabledToPlayPause = false //this one must be called after pause()
            }
        }
    }

    fun askPlayOrPause() {
        this.onNotificationPlayOrPause?.invoke()
    }

    fun askStop() {
        this.onNotificationStop?.invoke()
    }
}

class ForwardHandler : Handler() {

    companion object {
        const val MESSAGE_FORWARD = 1
        const val DELAY = 300L
    }

    private var player: com.github.florent37.assets_audio_player.Player? = null
    private var speed: Double = 1.0

    val isActive: Boolean
        get() = hasMessages(MESSAGE_FORWARD)

    fun start(player: com.github.florent37.assets_audio_player.Player, speed: Double) {
        this.player = player
        this.speed = speed
        removeMessages(MESSAGE_FORWARD)
        sendEmptyMessage(MESSAGE_FORWARD)
    }

    fun stop() {
        removeMessages(MESSAGE_FORWARD)
        this.player = null
    }

    override fun handleMessage(msg: Message?) {
        super.handleMessage(msg)
        if (msg?.what == MESSAGE_FORWARD) {
            this.player?.let {
                it.seekBy((DELAY * speed).toLong())
                sendEmptyMessageDelayed(MESSAGE_FORWARD, DELAY)
            }
        }
    }
}