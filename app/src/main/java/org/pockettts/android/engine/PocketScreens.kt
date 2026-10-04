@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.pockettts.android.engine

import android.content.res.ColorStateList
import android.os.Build
import android.view.ViewGroup
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

@Composable
internal fun PocketApp(app: MainActivity) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        bottomBar = {
            NavigationBar(modifier = Modifier.collapseWithIme(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                listOf(R.string.tab_reader, R.string.tab_voices, R.string.tab_settings).forEachIndexed { index, label ->
                    NavigationBarItem(selected = app.tab == index, onClick = { app.switchTab(index) }, modifier = Modifier.testTag("nav-$index"),
                        icon = { Icon(listOf(Icons.Default.Edit, Icons.Default.Person, Icons.Default.Settings)[index], null) },
                        label = { Text(stringResource(label)) })
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(Modifier.collapseWithIme().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(listOf(R.string.tab_reader, R.string.tab_voices, R.string.tab_settings)[app.tab]),
                    style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            }
            when (app.tab) {
                0 -> ReaderScreen(app)
                1 -> VoicesScreen(app)
                2 -> SettingsScreen(app)
            }
        }
    }
    AppSheets(app)
    app.confirmation?.let { prompt ->
        AlertDialog(onDismissRequest = { app.confirmation = null },
            modifier = Modifier.border(1.dp, LocalGlass.current.border, MaterialTheme.shapes.extraLarge),
            shape = MaterialTheme.shapes.extraLarge, containerColor = LocalGlass.current.sheet,
            title = { Text(prompt.title) },
            text = { Text(prompt.message, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { FilledTonalButton(onClick = { app.confirmation = null; prompt.confirm() },
                colors = if (prompt.destructive) ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    else ButtonDefaults.filledTonalButtonColors()) { Text(prompt.action) } },
            dismissButton = { TextButton(onClick = { app.confirmation = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    app.recordingDialog?.Content()
    app.videoDialog?.Content()
    if (app.preview.state.key != null) {
        GlassSheet(app.preview.state.title, app::stopPreview) {
            item { PreviewControls(app.preview) }
        }
    }
    app.renameTarget?.let { (_, voice) ->
        var name by remember(voice.id) { mutableStateOf(voice.displayName) }
        AlertDialog(onDismissRequest = { if (!app.busy) app.renameTarget = null },
            containerColor = LocalGlass.current.sheet, shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Rename voice") }, text = {
                Column {
                    OutlinedTextField(name, { name = it; app.renameError = null }, singleLine = true,
                        enabled = !app.busy, label = { Text(stringResource(R.string.voice_name)) },
                        isError = app.renameError != null, modifier = Modifier.testTag("rename-name"))
                    app.renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { FilledTonalButton(onClick = { app.renameVoice(name) }, enabled = !app.busy && name.trim().isNotEmpty() && name.trim().length <= 80,
                modifier = Modifier.testTag("rename-save")) { Text("Save") } },
            dismissButton = { TextButton(onClick = { app.renameTarget = null }, enabled = !app.busy) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable
private fun ReaderScreen(app: MainActivity) {
    val compact = LocalConfiguration.current.screenHeightDp < 560 || LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactEditorHeight = (maxHeight * .6f).coerceAtLeast(160.dp)
        // Small windows and large accessibility fonts scroll the whole layout; the editor
        // remains independently scrollable. Normal phones dedicate remaining height to text.
        Column(Modifier.fillMaxSize().testTag("reader-scroll").pointerInput(app) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                app.readingHighlight.gestureStarted()
                try { do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed }) }
                finally { app.readingHighlight.gestureEnded() }
            }
        }.then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentEditor(app, if (compact) Modifier.fillMaxWidth().height(compactEditorHeight)
                else Modifier.fillMaxWidth().weight(1f))
            PlaybackPanel(app)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
private fun DocumentEditor(app: MainActivity, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalGlass.current
    var focused by remember { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    DisposableEffect(app) {
        var relocation: kotlinx.coroutines.Job? = null
        app.readingHighlight.reveal = { rect -> relocation?.cancel(); relocation = scope.launch { requester.bringIntoView(rect) } }
        app.readingHighlight.cancelReveal = { relocation?.cancel() }
        onDispose { relocation?.cancel(); app.readingHighlight.reveal = null; app.readingHighlight.cancelReveal = null }
    }
    Surface(modifier.testTag("document"), shape = MaterialTheme.shapes.large, color = glass.card,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) colors.primary else glass.border)) {
        Column {
            FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ui_document), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant)
                if (app.editing) TextButton(onClick = app::clearDocument, enabled = app.documentReady) { Text(stringResource(R.string.reader_clear)) }
                FilterChip(selected = app.editing, onClick = { app.setEditing(!app.editing) }, enabled = app.documentReady,
                    label = { Text(stringResource(if (app.editing) R.string.reader_done_editing else R.string.reader_edit)) },
                    modifier = Modifier.testTag("reader-edit"))
            }
            AndroidView(factory = {
                (app.editor.parent as? ViewGroup)?.removeView(app.editor)
                app.editor.apply { setOnFocusChangeListener { _, hasFocus -> focused = hasFocus } }
            }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("reader-text").bringIntoViewRequester(requester), update = {
                it.isEnabled = app.documentReady
                it.setTextColor(colors.onSurface.toArgb())
                it.setHintTextColor(colors.onSurfaceVariant.toArgb())
                it.highlightColor = colors.primary.copy(alpha = .3f).toArgb()
                app.readingHighlight.update(app.snapshot, colors.primary.copy(alpha = .24f).toArgb())
                it.backgroundTintList = ColorStateList.valueOf(colors.primary.toArgb())
                if (Build.VERSION.SDK_INT >= 29) it.textCursorDrawable?.setTint(colors.primary.toArgb())
            })
            Text(app.documentInfo.ifBlank { stringResource(R.string.reader_starting) },
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaybackPanel(app: MainActivity) {
    val snapshot = app.snapshot
    val active = snapshot.state == ReaderPlaybackService.State.PLAYING || snapshot.state == ReaderPlaybackService.State.LOADING
    val paused = snapshot.state == ReaderPlaybackService.State.PAUSED
    GlassCard(Modifier.fillMaxWidth().testTag("playback"), padding = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PlaybackStatus(snapshot)
                if (snapshot.chunks > 0) Text("${snapshot.chunk} / ${snapshot.chunks}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { app.sheet = "voice" }, modifier = Modifier.weight(1f)) {
                Text(app.selection?.second?.displayName ?: stringResource(R.string.no_voice), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            TextButton(onClick = { app.sheet = "speed" }) { Text(PlaybackSpeed.label(app.speed)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = app::previousReaderChunk, enabled = snapshot.canPrevious, modifier = Modifier.testTag("reader-previous")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous chunk")
            }
            Button(onClick = if (active) app::pauseReader else app::startReaderPlayback,
                enabled = app.documentReady && !snapshot.exporting && app.selection != null,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(if (active) R.string.reader_pause else if (paused) R.string.reader_resume else R.string.reader_play))
            }
            IconButton(onClick = app::nextReaderChunk, enabled = snapshot.canNext, modifier = Modifier.testTag("reader-next")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next chunk")
            }
            OutlinedButton(onClick = app::stopReader, enabled = active || paused,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_stop)) }
        }
        Column(Modifier.collapseWithIme()) {
            var seeking by remember { mutableStateOf<Float?>(null) }
            var seekDuration by remember { mutableLongStateOf(0L) }
            val duration = snapshot.availableMs
            val progress = if (duration > 0) snapshot.positionMs.toFloat() / duration else 0f
            Slider(value = seeking ?: progress.coerceIn(0f, 1f),
                onValueChange = { if (seeking == null) seekDuration = duration; seeking = it },
                onValueChangeFinished = { seeking?.let { app.seekReader((it * seekDuration).toLong()) }; seeking = null },
                enabled = duration > 0, modifier = Modifier.fillMaxWidth().testTag("reader-seek")
                    .semantics { contentDescription = app.getString(R.string.reader_seek) },
                track = {
                    PlaybackWaveform(snapshot.waveform, snapshot.waveformBinSamples, snapshot.waveformSamples,
                        seeking?.let { (it * seekDuration).toLong() } ?: snapshot.positionMs, duration, app::seekReader,
                        "reader-waveform", app.getString(R.string.reader_waveform), height = 32.dp, interactive = false)
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${formatTime(seeking?.let { (it * seekDuration).toLong() } ?: snapshot.positionMs)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!snapshot.complete && duration > 0) Text(stringResource(R.string.reader_available), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = app::exportAudio, enabled = snapshot.complete && !snapshot.exporting) { Text(stringResource(R.string.reader_export)) }
            }
        }
    }
}

/** Measure using this animation frame's insets, not a visibility flag or a second timer. */
@Composable
private fun Modifier.collapseWithIme(): Modifier {
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    return clipToBounds().layout { measurable, constraints ->
        val full = maxOf(ime.getBottom(this), source.getBottom(this), target.getBottom(this))
        val fraction = if (full == 0) 0f else ime.getBottom(this).toFloat() / full
        val child = measurable.measure(constraints.copy(minHeight = 0))
        layout(child.width, (child.height * (1f - fraction)).toInt()) { child.placeRelative(0, 0) }
    }
}

@Composable
internal fun PlaybackWaveform(peaks: List<Float>, binSamples: Long, samples: Long, positionMs: Long,
                             durationMs: Long, seek: (Long) -> Unit, tag: String, description: String,
                             height: androidx.compose.ui.unit.Dp = 48.dp, interactive: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val currentSeek = rememberUpdatedState(seek)
    val interaction = if (!interactive) Modifier else Modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(positionMs.toFloat(), 0f..durationMs.coerceAtLeast(1).toFloat())
            setProgress { if (durationMs > 0) { seek(it.toLong()); true } else false }
        }
        .pointerInput(durationMs) {
            detectTapGestures { if (durationMs > 0) currentSeek.value((it.x / size.width.coerceAtLeast(1) * durationMs).toLong()) }
        }
    Canvas(Modifier.fillMaxWidth().height(height).testTag(tag).then(interaction)) {
        if (peaks.isEmpty() || samples <= 0) {
            drawLine(muted, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        } else {
            // Envelope bins have uniform sample widths, except the final partial bin.
            peaks.forEachIndexed { i, peak ->
                val a = i * binSamples
                val b = minOf(a + binSamples, samples)
                val x = ((a + b) * .5 / samples * size.width).toFloat()
                val h = (kotlin.math.sqrt(peak) * size.height).coerceAtLeast(2.dp.toPx())
                drawLine(if (x <= progress * size.width) accent else muted, Offset(x, (size.height - h) / 2),
                    Offset(x, (size.height + h) / 2), 2.dp.toPx(), StrokeCap.Round)
            }
            drawLine(accent, Offset(progress * size.width, 0f), Offset(progress * size.width, size.height), 2.dp.toPx())
        }
    }
}

@Composable
internal fun PlaybackStatus(snapshot: ReaderPlaybackService.Snapshot) {
    val label = stringResource(when (snapshot.state) {
        ReaderPlaybackService.State.IDLE -> R.string.reader_idle
        ReaderPlaybackService.State.LOADING -> R.string.reader_loading
        ReaderPlaybackService.State.PLAYING -> R.string.reader_playing_notification
        ReaderPlaybackService.State.PAUSED -> R.string.reader_paused
        ReaderPlaybackService.State.STOPPED -> R.string.reader_stopped
        ReaderPlaybackService.State.ERROR -> R.string.reader_error
    })
    val color = when (snapshot.state) {
        ReaderPlaybackService.State.PLAYING -> LocalGlass.current.success
        ReaderPlaybackService.State.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot.state == ReaderPlaybackService.State.LOADING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
        snapshot.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        if (snapshot.exporting) Text(stringResource(R.string.reader_exporting), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VoicesScreen(app: MainActivity) {
    LazyColumn(Modifier.fillMaxSize().testTag("voices-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Button(onClick = { app.sheet = "add" }, enabled = !app.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.voice_add))
            }
        }
        item { Notice(app) }
        if (app.packs.flatMap { it.voices }.isEmpty()) item { EmptyVoices(app) }
        app.packs.forEach { pack ->
            items(pack.voices, key = { pack.id + "/" + it.id }) { voice ->
                val selected = app.selection?.first?.id == pack.id && app.selection?.second?.id == voice.id
                GlassCard(Modifier.fillMaxWidth().semantics { this.selected = selected }.testTag("voice-${voice.id}"), selected = selected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(voice.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (selected) Icon(Icons.Default.CheckCircle, stringResource(R.string.ui_selected), tint = MaterialTheme.colorScheme.primary)
                        run {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !app.busy, modifier = Modifier.testTag("voice-actions-${voice.id}")) {
                                    Icon(Icons.Default.MoreVert, "Voice actions")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
                                    modifier = Modifier.testTag("voice-menu"), shape = MaterialTheme.shapes.medium,
                                    containerColor = LocalGlass.current.sheet, tonalElevation = 0.dp, shadowElevation = 2.dp,
                                    border = BorderStroke(1.dp, LocalGlass.current.border)) {
                                    DropdownMenuItem(text = { Text("Save .wav to device") }, onClick = { menu = false; app.exportVoiceWav(pack, voice) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(20.dp)) })
                                    if (voice.userCreated) {
                                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = LocalGlass.current.border)
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; app.beginRename(pack, voice) },
                                            leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(20.dp)) })
                                        DropdownMenuItem(text = { Text("Edit audio") }, onClick = { menu = false; app.editVoice(pack, voice) },
                                            leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) })
                                        if (voice.audioEdited) DropdownMenuItem(text = { Text("Restore Original") }, onClick = { menu = false; app.restoreVoice(pack, voice) },
                                            leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)) })
                                        HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = LocalGlass.current.border)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.voice_delete)) }, onClick = { menu = false; app.deleteVoice(pack, voice) },
                                            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error),
                                            leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(20.dp)) })
                                    }
                                }
                            }
                        }
                    }
                    Text(pack.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (selected) Text(stringResource(R.string.ui_selected), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.previewVoice(pack, voice) }, enabled = !app.busy) { Text(stringResource(R.string.voice_preview)) }
                        FilledTonalButton(onClick = { app.selectVoice(pack, voice) }, enabled = !selected && !app.busy) { Text(stringResource(R.string.voice_use)) }
                        TextButton(onClick = { app.detail = pack to voice }, enabled = !app.busy) { Text(stringResource(R.string.ui_details)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVoices(app: MainActivity) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.no_voice), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ui_voice_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        FilledTonalButton(onClick = { app.switchTab(2) }) { Text(stringResource(R.string.tab_settings)) }
    }
}

@Composable
private fun SettingsScreen(app: MainActivity) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Notice(app) }
        item {
            GlassCard(Modifier.fillMaxWidth().testTag("voice-backup")) {
                SectionTitle("Voice backup")
                Text("Save your custom voices before uninstalling. The ZIP contains private voice recordings, names and original audio. Keep it safe; nothing is uploaded by Pocket TTS.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = app::exportVoices, enabled = !app.busy,
                        modifier = Modifier.testTag("backup-export")) { Text("Export Voices") }
                    OutlinedButton(onClick = app::importVoices, enabled = !app.busy,
                        modifier = Modifier.testTag("backup-import")) { Text("Import Voices") }
                }
                Text("Conflicts are restored as new copies, never overwritten. Save to a local folder for an offline backup.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.reader_appearance))
                ChoiceRow(appearanceLabel(app.appearance), stringResource(R.string.ui_appearance_hint)) { app.sheet = "appearance" }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.engine_settings))
                ChoiceRow(stringResource(R.string.voice_default), app.selection?.second?.displayName ?: stringResource(R.string.no_voice)) { app.sheet = "voice" }
                ChoiceRow(stringResource(R.string.generation_parameters), stringResource(R.string.ui_engine_hint)) { app.sheet = "engine" }
                OutlinedButton(onClick = app::openTtsSettings) { Text(stringResource(R.string.open_android_tts)) }
            }
        }
        item {
            OutlinedButton(onClick = app::showAbout, modifier = Modifier.fillMaxWidth().testTag("about-entry")) { Text(stringResource(R.string.about_app)) }
        }
    }
}

@Composable
private fun Notice(app: MainActivity) {
    if (app.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (app.notice.isNotBlank()) Text(app.notice, style = MaterialTheme.typography.bodyMedium,
        color = if (app.noticeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
}

@Composable
private fun ChoiceRow(title: String, subtitle: String? = null, selected: Boolean? = null, action: () -> Unit) {
    Surface(onClick = action, shape = MaterialTheme.shapes.medium,
        color = if (selected == true) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected == true) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { if (selected != null) this.selected = selected }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (selected != null) RadioButton(selected = selected, onClick = null)
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun GlassSheet(title: String, onDismiss: () -> Unit, footer: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val density = LocalDensity.current
    var expandedOffset by remember { mutableFloatStateOf(Float.NaN) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    val stateHolder = remember { arrayOfNulls<SheetState>(1) }
    val confirmDismiss = remember(density) { { value: SheetValue ->
        val offset = stateHolder[0]?.let { runCatching { it.requireOffset() }.getOrNull() }
        val distance = if (offset == null) 0f else offset - expandedOffset
        // Native settling still owns scrolling/animation. Veto short drags, even fast
        // flings; explicit dismissal at the expanded anchor (scrim/back) remains valid.
        value != SheetValue.Hidden || !distance.isFinite() || distance <= 1f ||
            distance >= minOf(with(density) { 224.dp.toPx() }, sheetHeight * .35f)
    } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmDismiss)
    stateHolder[0] = sheetState
    val contentScroll = remember(sheetState, density) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val distance = runCatching { sheetState.requireOffset() - expandedOffset }.getOrDefault(0f)
                // Do not turn a list's remaining downward fling into a zero-distance
                // dismissal. Consume only leftover velocity, never content scrolling.
                return if (available.y > 0 && (!distance.isFinite() ||
                    distance < minOf(with(density) { 224.dp.toPx() }, sheetHeight * .35f)))
                    Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue to runCatching { sheetState.requireOffset() }.getOrDefault(Float.NaN) }.collect { (value, offset) ->
            if (value == SheetValue.Expanded && offset.isFinite())
                expandedOffset = if (expandedOffset.isFinite()) minOf(expandedOffset, offset) else offset
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = Modifier.testTag("glass-sheet").onSizeChanged { sheetHeight = it.height.toFloat(); expandedOffset = Float.NaN },
        shape = shape, containerColor = LocalGlass.current.sheet, tonalElevation = 0.dp,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalDivider(color = LocalGlass.current.border)
                BottomSheetDefaults.DragHandle(Modifier.testTag("sheet-handle"))
            }
        }) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("sheet-close")) {
                Icon(Icons.Default.Close, stringResource(R.string.ui_close))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 600.dp).nestedScroll(contentScroll).testTag("sheet-content"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
        footer?.invoke()
    }
}

@Composable
private fun AppSheets(app: MainActivity) {
    val dismiss = { app.sheet = null; app.stopPreview() }
    when (app.sheet) {
        "about" -> AboutSheet(dismiss)
        "appearance" -> GlassSheet(stringResource(R.string.reader_appearance), dismiss) {
            items(listOf("system", "light", "dark", "amoled")) { mode ->
                ChoiceRow(appearanceLabel(mode), selected = app.appearance == mode) { app.setAppearance(mode); app.sheet = null }
            }
        }
        "speed" -> GlassSheet(stringResource(R.string.reader_speed), dismiss) {
            item { SpeedControl(app.speed, app::selectSpeed) }
        }
        "voice" -> GlassSheet(stringResource(R.string.voice), dismiss) {
            if (app.packs.flatMap { it.voices }.isEmpty()) item {
                Text(stringResource(R.string.ui_voice_empty))
                TextButton(onClick = { app.sheet = null; app.switchTab(2) }) { Text(stringResource(R.string.tab_settings)) }
            }
            app.packs.forEach { pack ->
                items(pack.voices, key = { pack.id + "/" + it.id }) { voice ->
                    ChoiceRow(voice.displayName, pack.displayName,
                        app.selection?.first?.id == pack.id && app.selection?.second?.id == voice.id) {
                        app.selectVoice(pack, voice); app.sheet = null
                    }
                }
            }
        }
        "add" -> AddVoiceSheet(app)
        "engine" -> GlassSheet(stringResource(R.string.generation_parameters), dismiss) {
            if (app.currentPack == null) item { Text(stringResource(R.string.status_no_model)) }
            else {
                item { Text(app.currentPack!!.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(app.parameters.size) { index ->
                    OutlinedTextField(value = app.parameters[index], onValueChange = { value ->
                        app.parameters = app.parameters.toMutableList().also { it[index] = value }
                    }, label = { Text(stringResource(listOf(R.string.temperature_label, R.string.lsd_steps_label,
                        R.string.cpu_threads_label, R.string.sentence_pause_label, R.string.max_text_tokens_label)[index])) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (index == 0) KeyboardType.Decimal else KeyboardType.Number))
                }
                item { Notice(app) }
                item { Button(onClick = app::saveSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_selection)) } }
            }
        }
    }
    app.detail?.let { (pack, voice) ->
        GlassSheet(voice.displayName, { app.detail = null; app.stopPreview() }) {
            item {
                Text(pack.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${pack.languageTag} · ${pack.precision.uppercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (voice.userCreated) R.string.voice_user else R.string.voice_pack), Modifier.padding(top = 12.dp))
            }
            item { Text(voice.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ModelPackRepository.recordingSource(pack, voice, false) != null) item {
                SectionTitle(stringResource(R.string.df_saved_sources))
                OutlinedButton(onClick = { app.previewVoice(pack, voice, false) }) { Text(stringResource(R.string.df_before)) }
                if (ModelPackRepository.recordingSource(pack, voice, true) != null)
                    OutlinedButton(onClick = { app.previewVoice(pack, voice, true) }) { Text(stringResource(R.string.df_after)) }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { app.previewVoice(pack, voice) }) { Text(stringResource(R.string.voice_preview)) }
                    Button(onClick = { app.selectVoice(pack, voice); app.detail = null; app.switchTab(0) }) { Text(stringResource(R.string.voice_use)) }
                    if (voice.userCreated) TextButton(onClick = { app.deleteVoice(pack, voice) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.voice_delete)) }
                }
                if (voice.userCreated) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.beginRename(pack, voice) }) { Text("Rename") }
                    FilledTonalButton(onClick = { app.editVoice(pack, voice) }) { Text("Edit audio") }
                    if (voice.audioEdited) OutlinedButton(onClick = { app.restoreVoice(pack, voice) }) { Text("Restore Original") }
                }
                OutlinedButton(onClick = { app.exportVoiceWav(pack, voice) }, modifier = Modifier.fillMaxWidth()) { Text("Save .wav to device") }
            }
        }
    }
}

@Composable
private fun appearanceLabel(mode: String): String = stringResource(when (mode) {
    "light" -> R.string.reader_appearance_light
    "dark" -> R.string.reader_appearance_dark
    "amoled" -> R.string.reader_appearance_amoled
    else -> R.string.reader_appearance_system
})

@Composable
internal fun DiagnosticScreen(message: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())) {
            GlassCard { Text(message, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

private fun formatTime(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
