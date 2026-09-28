package org.pockettts.android.engine

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread transport; one serial worker for native synthesis and disk export. */
class ReaderPlaybackService : Service() {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR }
    data class Snapshot(
        val state: State, val chunk: Int = 0, val chunks: Int = 0, val message: String? = null,
        val positionMs: Long = 0, val availableMs: Long = 0, val complete: Boolean = false,
        val title: String = "Pocket TTS Reader", val exporting: Boolean = false,
        val document: String? = null, val rangeStart: Int = -1, val rangeEnd: Int = -1,
        val waveform: List<Float> = emptyList(), val waveformBinSamples: Long = 0, val waveformSamples: Long = 0
    )
    interface Listener { fun onReaderStateChanged(snapshot: Snapshot) }
    inner class LocalBinder : Binder() { fun service() = this@ReaderPlaybackService }
    private class Session(val directory: File, val title: String, var speed: Float, val document: String) {
        val cancelled = AtomicBoolean(false)
        val files = mutableListOf<File>() // Main thread only; worker posts completed files.
        val timeline = AudioTimeline()
        val ranges = mutableListOf<ReaderTextChunker.Range>()
        var peaks: List<Float> = emptyList()
        var binSamples = 0L
        var samples = 0L
        var complete = false
        var total = 0
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<Listener>()
    private var session: Session? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var index = 0
    private var offsetMs = 0L
    private var playWhenReady = false
    private var foreground = false
    private var destroyed = false
    private var exporting = false
    private var lastMessage: String? = null
    private var state = State.IDLE
    private lateinit var mediaSession: MediaSession
    private var focus: AudioFocusRequest? = null
    private val stopUnused = Runnable { if (!foreground && !exporting) stopSelf() }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            publish(false)
            main.postDelayed(this, 500)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.reader_notification_channel), NotificationManager.IMPORTANCE_LOW))
        mediaSession = MediaSession(this, "PocketTtsReader").apply {
            setPlaybackToLocal(attributes)
            setSessionActivity(openReader())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
                override fun onSkipToNext() { session?.let { if (index + 1 < it.files.size) seekTo(it.timeline.start(index + 1)) } }
                override fun onSkipToPrevious() { session?.let { seekTo(it.timeline.start((index - 1).coerceAtLeast(0))) } }
                override fun onCustomAction(action: String, extras: Bundle?) { if (action == STOP) stop() }
            }, main)
        }
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        // This service exclusively owns the subtree. Remove stale caches after process death.
        val root = File(cacheDir, "reader-audio").apply { mkdirs() }
        worker.execute { root.listFiles()?.forEach { it.deleteRecursively() } }
        main.post(tick)
    }
    override fun onBind(intent: Intent?): IBinder {
        main.removeCallbacks(stopUnused)
        startService(Intent(this, ReaderPlaybackService::class.java))
        return LocalBinder()
    }
    override fun onUnbind(intent: Intent?): Boolean { main.postDelayed(stopUnused, 30_000); return true }
    override fun onRebind(intent: Intent?) { main.removeCallbacks(stopUnused) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> mediaSession.controller.transportControls.pause()
            RESUME -> mediaSession.controller.transportControls.play()
            STOP -> mediaSession.controller.transportControls.stop()
        }
        return START_NOT_STICKY
    }
    fun addListener(listener: Listener) { listeners += listener; listener.onReaderStateChanged(snapshot()) }
    fun removeListener(listener: Listener) { listeners -= listener }
    fun isBusy(): Boolean = session?.let { !it.complete && !it.cancelled.get() } == true || playWhenReady || exporting

    fun play(text: String, packId: String, voiceId: String, speed: Float) {
        if (destroyed) return
        setSpeed(speed)
        if (state == State.PAUSED) { resume(); return }
        if (state == State.LOADING || state == State.PLAYING || exporting) return
        if (text.isBlank()) { fail(getString(R.string.reader_error_empty)); return }
        val pack = ModelPackRepository.find(this, packId)
        val voice = pack?.voices?.firstOrNull { it.id == voiceId }
        if (pack == null || voice == null) { fail(getString(R.string.reader_error_voice)); return }
        disposeSession()
        val next = Session(File(cacheDir, "reader-audio/${UUID.randomUUID()}"),
            text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(64) ?: "Pocket TTS Reader", speed.coerceIn(.5f, 2f), text)
        session = next
        playWhenReady = true
        state = State.LOADING
        lastMessage = null
        ensureForeground()
        if (!requestFocus()) { fail(getString(R.string.reader_error_audio_focus)); return }
        publish()
        worker.execute { generate(next, text, pack, voice) }
    }
    private fun generate(s: Session, text: String, pack: ModelPack, voice: PackVoice) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:synthesis")
        try {
            if (s.cancelled.get()) return
            wake.acquire(30 * 60 * 1000L)
            check(s.directory.mkdirs())
            val peaks = AudioPeaks()
            val chunks = ReaderTextChunker.ranges(text, (ModelPackRepository.maxTextTokens(this, pack) * 4).coerceIn(120, 800))
                for ((i, range) in chunks.withIndex()) {
                    val chunk = text.substring(range.start, range.end)
                    if (s.cancelled.get()) break
                    if (s.directory.usableSpace < 32L * 1024 * 1024) throw java.io.IOException("Insufficient audio cache space")
                    val file = File(s.directory, "$i.wav")
                    var sinkError: Throwable? = null
                    var bytes = 0L
                    PcmWav.Writer(file).use { output ->
                        val ok = PocketEngine.withEngine(this, pack) { engine ->
                            if (s.cancelled.get()) return@withEngine false
                            engine.synthesize(chunk, voice.fileName, object : NativePocketTts.AudioSink {
                            override fun onAudio(samples: FloatArray): Boolean {
                                if (s.cancelled.get()) return false
                                return try {
                                    if (!wake.isHeld) wake.acquire(30 * 60 * 1000L)
                                    output.write(samples); peaks.add(samples); true
                                } catch (error: Throwable) { sinkError = error; false }
                            }
                            })
                        }
                        sinkError?.let { throw it }
                        bytes = output.bytes
                        if (!s.cancelled.get()) check(ok && bytes > 0) { "Synthesis returned no usable audio" }
                    }
                    if (s.cancelled.get()) break
                    val envelope = peaks.snapshot()
                    val binSamples = peaks.binSamples()
                    val sampleCount = peaks.samples
                    main.post {
                        if (session === s && !destroyed && !s.cancelled.get()) {
                            s.files += file
                            s.ranges += range
                            s.peaks = envelope; s.binSamples = binSamples; s.samples = sampleCount
                            s.timeline.append((bytes * 1000 / (PcmWav.RATE * 2)).coerceAtLeast(1))
                            if (player == null && playWhenReady) openChunk(index, offsetMs)
                            publish()
                        }
                    }
                }
            main.post {
                if (session === s && !destroyed && !s.cancelled.get()) {
                    s.complete = true
                    s.total = s.files.size
                    if (player == null && index >= s.files.size && playWhenReady) finishPlayback()
                    publish()
                }
            }
        } catch (error: Throwable) {
            Log.e("PocketTTS", "READER_GENERATION_FAILED", error)
            main.post { if (session === s && !destroyed && !s.cancelled.get()) fail(userError(error)) }
        } finally { if (wake.isHeld) wake.release() }
    }
    private fun openChunk(next: Int, offset: Long = 0) {
        val s = session ?: return
        releasePlayer()
        index = next; offsetMs = offset
        if (next !in s.files.indices) {
            if (s.complete || s.cancelled.get()) finishPlayback() else { state = if (playWhenReady) State.LOADING else State.PAUSED; publish() }
            return
        }
        state = if (playWhenReady) State.LOADING else State.PAUSED
        try {
            val audio = MediaPlayer()
            player = audio
            audio.setAudioAttributes(attributes)
            audio.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            audio.setDataSource(s.files[next].absolutePath)
            audio.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                it.setOnSeekCompleteListener { ready -> if (player === ready) { offsetMs = ready.currentPosition.toLong(); startPrepared() } }
                if (offsetMs > 0) it.seekTo(offsetMs, MediaPlayer.SEEK_CLOSEST) else startPrepared()
            }
            audio.setOnCompletionListener { if (player === it) openChunk(index + 1) }
            audio.setOnErrorListener { failed, what, extra ->
                if (player === failed) {
                    Log.e("PocketTTS", "READER_AUDIO_FAILED $what/$extra")
                    fail(getString(R.string.reader_error_output))
                }
                true
            }
            audio.prepareAsync()
        } catch (error: Exception) { Log.e("PocketTTS", "READER_OPEN_FAILED", error); fail(getString(R.string.reader_error_output)) }
        publish()
    }
    private fun startPrepared() {
        val audio = player ?: return
        if (!prepared) return
        if (playWhenReady) {
            try {
                audio.playbackParams = PlaybackParams().setSpeed(session?.speed ?: 1f).setPitch(1f)
                audio.start(); state = State.PLAYING
            } catch (error: Exception) { Log.e("PocketTTS", "READER_START_FAILED", error); fail(getString(R.string.reader_error_output)); return }
        } else state = State.PAUSED
        publish()
    }
    fun setSpeed(value: Float) {
        if (destroyed) return
        session?.speed = PlaybackSpeed.normalize(value)
        // Nonzero PlaybackParams starts a prepared player. Defer while paused/seeking.
        if (prepared && state == State.PLAYING && playWhenReady) {
            try { player?.playbackParams = PlaybackParams().setSpeed(session?.speed ?: 1f).setPitch(1f) }
            catch (error: Exception) { Log.e("PocketTTS", "READER_SPEED_FAILED", error); fail(getString(R.string.reader_error_output)); return }
        }
        publish()
    }
    fun pause() {
        if (!playWhenReady) return
        playWhenReady = false
        try {
            if (prepared && player?.isPlaying == true) player?.pause()
        } catch (error: IllegalStateException) {
            Log.e("PocketTTS", "READER_PAUSE_FAILED", error)
            fail(getString(R.string.reader_error_output)); return
        }
        state = State.PAUSED
        abandonFocus(); publish()
    }
    fun resume() {
        if (destroyed) return
        val s = session ?: return
        if (state == State.PLAYING || (state == State.LOADING && playWhenReady)) return
        if (s.files.isEmpty() && s.cancelled.get()) return
        ensureForeground()
        if (!requestFocus()) { lastMessage = getString(R.string.reader_error_audio_focus); publish(); return }
        lastMessage = null; playWhenReady = true
        if (index >= s.files.size && s.complete) { index = 0; offsetMs = 0 }
        if (player == null) openChunk(index, offsetMs) else startPrepared()
    }
    fun stop() {
        session?.cancelled?.set(true)
        playWhenReady = false
        releasePlayer(); index = 0; offsetMs = 0
        state = State.STOPPED; lastMessage = null
        abandonFocus(); publish(); endForeground()
    }
    fun seekTo(positionMs: Long) {
        val s = session ?: return
        if (s.files.isEmpty()) return
        val (target, offset) = s.timeline.locate(positionMs)
        openChunk(target, offset)
    }
    private fun finishPlayback() {
        releasePlayer(); playWhenReady = false; state = State.IDLE
        abandonFocus(); publish(); endForeground()
    }
    fun export(uri: Uri) {
        val s = session
        if (s == null || !s.complete) { lastMessage = getString(R.string.reader_export_unavailable); publish(); return }
        if (exporting) return
        val files = s.files.toList()
        exporting = true; lastMessage = getString(R.string.reader_exporting)
        ensureForeground(); publish()
        worker.execute {
            val result = runCatching {
                require(uri.scheme == "content")
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { PcmWav.concatenate(files, it) }
            }
            result.exceptionOrNull()?.let { Log.e("PocketTTS", "READER_EXPORT_FAILED", it) }
            main.post {
                if (!destroyed) {
                    exporting = false
                    lastMessage = getString(if (result.isSuccess) R.string.reader_exported else R.string.reader_export_failed)
                    publish()
                    if (!playWhenReady && s.complete) endForeground()
                }
            }
        }
    }
    private fun snapshot(): Snapshot {
        val s = session
        val position = if (prepared) runCatching { player?.currentPosition?.toLong() ?: offsetMs }.getOrDefault(offsetMs) else offsetMs
        val range = if (prepared && (state == State.PLAYING || state == State.PAUSED)) s?.ranges?.getOrNull(index) else null
        return Snapshot(state, if (s == null) 0 else (index + 1).coerceAtMost(s.files.size), s?.total ?: 0, lastMessage,
            (s?.timeline?.start(index.coerceAtMost(s.files.size)) ?: 0) + position,
            s?.timeline?.duration ?: 0, s?.complete ?: false, s?.title ?: "Pocket TTS Reader", exporting,
            s?.document, range?.start ?: -1, range?.end ?: -1,
            if (state == State.STOPPED || state == State.ERROR) emptyList() else s?.peaks ?: emptyList(), s?.binSamples ?: 0, s?.samples ?: 0)
    }
    @android.annotation.SuppressLint("NotificationPermission") // MediaStyle notifications with a session token are exempt on Android 13+.
    private fun publish(notification: Boolean = true) {
        if (destroyed) return
        val value = snapshot()
        var actions = PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE
        actions = actions or if (playWhenReady) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        if (value.availableMs > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (index > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (index + 1 < (session?.files?.size ?: 0)) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        val androidState = when (state) {
            State.PLAYING -> PlaybackState.STATE_PLAYING
            State.PAUSED -> PlaybackState.STATE_PAUSED
            State.LOADING -> PlaybackState.STATE_BUFFERING
            State.ERROR -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_STOPPED
        }
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(androidState, value.positionMs, if (state == State.PLAYING) session?.speed ?: 1f else 0f)
            .setBufferedPosition(value.availableMs)
            .addCustomAction(STOP, getString(R.string.reader_stop), R.drawable.ic_stop).build())
        mediaSession.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, value.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Pocket TTS Reader")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (value.complete) value.availableMs else -1).build())
        if (notification && foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        listeners.toList().forEach { it.onReaderStateChanged(value) }
    }
    private fun ensureForeground() {
        if (!foreground) {
            startService(Intent(this, ReaderPlaybackService::class.java))
            startForeground(NOTIFICATION, notification())
            foreground = true; mediaSession.isActive = true
        }
    }
    private fun endForeground() {
        if (exporting) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false; mediaSession.isActive = false
        if (listeners.isEmpty()) main.postDelayed(stopUnused, 30_000)
    }
    private fun openReader() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        fun action(name: String, icon: Int, label: Int): Notification.Action {
            val pending = PendingIntent.getService(this, name.hashCode(), Intent(this, ReaderPlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), pending).build()
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_reader)
            .setContentTitle(session?.title ?: getString(R.string.reader_title))
            .setContentText(lastMessage ?: getString(when(state) { State.PAUSED -> R.string.reader_paused; State.LOADING -> R.string.reader_loading; else -> R.string.reader_playing_notification }))
            .setContentIntent(openReader()).setOnlyAlertOnce(true).setOngoing(playWhenReady || exporting)
            .addAction(if (playWhenReady) action(PAUSE, android.R.drawable.ic_media_pause, R.string.reader_pause) else action(RESUME, android.R.drawable.ic_media_play, R.string.reader_resume))
            .addAction(action(STOP, R.drawable.ic_stop, R.string.reader_stop))
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }
    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
                if (change < 0) pause() // Speech pauses for ducking too; resume is explicit.
            }, main).build()
        if (getSystemService(AudioManager::class.java).requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request; return true
    }
    private fun abandonFocus() { focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null }
    private fun releasePlayer() {
        val old = player
        player = null; prepared = false
        old?.release()
    }
    private fun fail(message: String) {
        session?.cancelled?.set(true); playWhenReady = false
        releasePlayer(); abandonFocus(); state = State.ERROR; lastMessage = message
        publish(); endForeground()
    }
    private fun userError(error: Throwable): String = when (error) {
        is UnsatisfiedLinkError -> getString(R.string.reader_error_native)
        is OutOfMemoryError -> getString(R.string.reader_error_memory)
        is java.io.IOException -> getString(R.string.reader_error_storage)
        else -> getString(R.string.reader_error_synthesis)
    }
    private fun disposeSession() {
        val old = session
        old?.cancelled?.set(true); releasePlayer()
        if (old != null) worker.execute { old.directory.deleteRecursively() }
        session = null; index = 0; offsetMs = 0
    }
    override fun onDestroy() {
        stop(); destroyed = true
        main.removeCallbacks(tick); main.removeCallbacks(stopUnused); unregisterReceiver(noisyReceiver); mediaSession.release(); listeners.clear()
        disposeSession(); worker.shutdown()
        super.onDestroy()
    }
    private companion object {
        const val CHANNEL = "reader_playback"; const val NOTIFICATION = 42
        const val PAUSE = "reader.PAUSE"; const val RESUME = "reader.RESUME"; const val STOP = "reader.STOP"
    }
}
