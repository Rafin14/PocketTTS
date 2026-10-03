package org.pockettts.android.engine

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import java.io.File

/** One foreground sample player shared by preview, trim and video controls. Main-thread only. */
internal class SamplePreview(private val context: Context) : AutoCloseable {
    data class State(val key: String? = null, val title: String = "", val loading: Boolean = false,
        val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0)
    var state by mutableStateOf(State())
        private set
    var speed by mutableFloatStateOf(PlaybackSpeed.read(context))
        private set
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var prepared = false
    private var playWhenReady = false
    private var seeking = false
    private var queuedSeek: Int? = null
    private var retainOnCompletion = false
    private var onError: () -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val tick = object : Runnable {
        override fun run() {
            val audio = player ?: return
            if (!prepared) return
            try {
                if (!seeking) state = state.copy(positionMs = audio.currentPosition)
                if (state.playing) main.postDelayed(this, 100)
            } catch (_: IllegalStateException) { fail() }
        }
    }
    fun play(file: File, title: String = file.nameWithoutExtension, key: String = file.absolutePath,
             positionMs: Int = 0, autoPlay: Boolean = true, retain: Boolean = false, onError: () -> Unit) {
        open(title, key, positionMs, autoPlay, retain, { it.setDataSource(file.absolutePath) }, onError)
    }
    fun video(source: android.content.res.AssetFileDescriptor, surface: android.view.Surface, title: String,
              positionMs: Int, autoPlay: Boolean, onError: () -> Unit) {
        open(title, "video", positionMs, autoPlay, true, {
            it.setSurface(surface)
            if (source.declaredLength < 0) it.setDataSource(source.fileDescriptor)
            else it.setDataSource(source.fileDescriptor, source.startOffset, source.declaredLength)
        }, onError)
    }
    private fun open(title: String, key: String, positionMs: Int, autoPlay: Boolean, retain: Boolean,
                     configure: (MediaPlayer) -> Unit, onError: () -> Unit) {
        close()
        this.onError = onError
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ if (focus === request && it < 0) close() }, main).build()
        if (context.getSystemService(AudioManager::class.java).requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onError(); return
        }
        focus = request
        state = State(key, title, loading = true, positionMs = positionMs.coerceAtLeast(0))
        playWhenReady = autoPlay; retainOnCompletion = retain
        try {
            val audio = MediaPlayer()
            player = audio // Install ownership before any asynchronous callbacks.
            audio.setAudioAttributes(attributes)
            configure(audio)
            audio.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                state = state.copy(loading = false, durationMs = it.duration.coerceAtLeast(0))
                val target = queuedSeek ?: positionMs; queuedSeek = null
                seekTo(target)
            }
            audio.setOnSeekCompleteListener {
                if (player !== it) return@setOnSeekCompleteListener
                seeking = false
                val next = queuedSeek; queuedSeek = null
                if (next != null) seekTo(next) else {
                    state = state.copy(positionMs = it.currentPosition)
                    if (playWhenReady) startPrepared()
                }
            }
            audio.setOnCompletionListener {
                if (player === it) {
                    if (!retainOnCompletion) close() else {
                        main.removeCallbacks(tick); playWhenReady = false
                        state = state.copy(playing = false, positionMs = state.durationMs)
                    }
                }
            }
            audio.setOnErrorListener { failed, _, _ -> if (player === failed) fail(); true }
            audio.prepareAsync()
        } catch (_: Exception) { fail() }
    }
    fun toggle() {
        if (player == null) return
        playWhenReady = !playWhenReady
        if (!prepared) return
        if (playWhenReady) {
            if (state.positionMs >= state.durationMs) seekTo(0) else if (!seeking) startPrepared()
        } else {
            try {
                // A rapid second toggle can arrive before the initial seek/start completes.
                if (state.playing) player?.pause()
                state = state.copy(playing = false, positionMs = if (seeking) state.positionMs else player?.currentPosition ?: state.positionMs)
                main.removeCallbacks(tick)
            } catch (_: IllegalStateException) { fail() }
        }
    }
    private fun startPrepared() {
        try {
            player?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            player?.start()
            state = state.copy(playing = true)
            main.removeCallbacks(tick); main.post(tick)
        } catch (_: Exception) { fail() }
    }
    fun changeSpeed(value: Float) {
        speed = PlaybackSpeed.normalize(value)
        if (prepared && state.playing) {
            try { player?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
            catch (_: Exception) { fail() }
        }
    }
    fun seekTo(positionMs: Int) {
        if (!prepared) {
            if (player != null) { queuedSeek = positionMs; state = state.copy(positionMs = positionMs.coerceAtLeast(0)) }
            return
        }
        try {
            val position = positionMs.coerceIn(0, state.durationMs)
            if (seeking) { queuedSeek = position; state = state.copy(positionMs = position); return }
            seeking = true
            player?.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
            state = state.copy(positionMs = position)
        } catch (_: IllegalStateException) { fail() }
    }
    private fun fail() {
        val notify = onError
        close()
        notify()
    }
    override fun close() {
        main.removeCallbacks(tick)
        val old = player
        player = null; prepared = false; playWhenReady = false; seeking = false; queuedSeek = null
        old?.release()
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null; state = State(); onError = {}
    }
}
