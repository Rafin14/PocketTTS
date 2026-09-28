package org.pockettts.android.engine

import android.app.Activity
import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal class VideoImportDialog(
    private val activity: Activity, private val uri: Uri,
    private val extracted: (File) -> Unit, private val dismissed: () -> Unit,
    private val chooseAnother: (() -> Unit)? = null
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancel = CancellationSignal()
    @Volatile private var closed = false
    private var transferred = false
    private val file = File(activity.cacheDir, "voice-video-${UUID.randomUUID()}.wav")
    private var source: AssetFileDescriptor? = null
    private var surface: Surface? = null
    private var resumePosition = 0
    internal val player = SamplePreview(activity)
    internal var info by mutableStateOf<VideoAudioExtractor.Info?>(null)
        private set
    private var name by mutableStateOf("Selected video")
    internal var error by mutableStateOf<String?>(null)
        private set
    internal var extracting by mutableStateOf(false)
        private set

    init {
        player.changeSpeed(1f)
        worker.execute {
            var descriptor: AssetFileDescriptor? = null
            val result = runCatching {
                val details = VideoAudioExtractor.inspect(activity, uri, cancel)
                val title = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancel)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment ?: "Selected video"
                descriptor = activity.contentResolver.openAssetFileDescriptor(uri, "r", cancel)
                requireNotNull(descriptor)
                details to title
            }
            main.post {
                if (closed) { descriptor?.close(); return@post }
                result.onSuccess { (details, title) -> source = descriptor; info = details; name = title; prepare(false) }
                    .onFailure { descriptor?.close(); error = "Cannot open video: ${it.message ?: "unsupported or damaged file"}. Choose another video." }
            }
        }
    }

    private fun prepare(play: Boolean) {
        if (closed || extracting) return
        val fd = source ?: return
        val view = surface ?: return
        player.video(fd, view, name, resumePosition, play) { error = "This device cannot play the video preview. You can still try extracting its audio." }
    }

    internal fun extract() {
        if (closed || extracting || info == null) return
        extracting = true; error = null; player.close()
        worker.execute {
            val result = runCatching { VideoAudioExtractor.extract(activity, uri, file, cancel) }
            main.post {
                if (closed) return@post
                extracting = false
                result.onSuccess {
                    // Ownership passes to the existing recording/import controller until save or cancel.
                    runCatching { extracted(file); transferred = true }.onFailure { error = it.message; file.delete() }
                    if (transferred) close()
                }.onFailure { error = "Audio extraction failed: ${it.message ?: "unsupported or damaged audio"}. Try another video or check free storage." }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true; cancel.cancel(); player.close()
        source?.close(); source = null; surface?.release(); surface = null
        worker.execute { if (!transferred) file.delete() }; worker.shutdown()
        dismissed()
    }

    @Composable
    fun Content() {
        GlassSheet("Extract from Video", ::close, footer = {
            Button(onClick = ::extract, enabled = info != null && !extracting,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("video-extract")) {
                Text(if (extracting) "Extracting audio…" else "Extract audio & trim")
            }
        }) {
            item {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text("Choose one speaker in the next step. Audio stays on this device. Up to 30 minutes / 256 MB of decoded audio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                info?.let { Text("${audioTime(it.durationMs)} · ${it.audioMime}", style = MaterialTheme.typography.bodySmall) }
            }
            if (info != null && !extracting) item {
                AndroidView(factory = { context -> TextureView(context).apply {
                    contentDescription = "Selected video preview"
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            surface = Surface(texture); prepare(false)
                        }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            resumePosition = player.state.positionMs; player.close(); surface?.release(); surface = null
                            return true
                        }
                    }
                } }, modifier = Modifier.fillMaxWidth().aspectRatio(requireNotNull(info).aspectRatio.coerceIn(.25f, 4f)).testTag("video-preview"))
            }
            item {
                if (extracting || (info == null && error == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (info != null && !extracting) {
                    if (player.state.key != null) PreviewControls(player, expanded = true)
                    else OutlinedButton(onClick = { resumePosition = 0; prepare(true) }, modifier = Modifier.fillMaxWidth()) { Text("Play video") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("video-error")) }
                if (error != null && !extracting && chooseAnother != null) OutlinedButton(
                    onClick = { close(); chooseAnother.invoke() }, modifier = Modifier.fillMaxWidth().testTag("video-replace")) {
                    Text("Choose another video")
                }
                TextButton(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text("Cancel / return to Voices") }
            }
        }
    }
}
