package org.pockettts.android.engine

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    internal var appearance by mutableStateOf("system")
        private set
    internal var tab by mutableIntStateOf(0)
    internal var sheet by mutableStateOf<String?>(null)
    internal var detail by mutableStateOf<Pair<ModelPack, PackVoice>?>(null)
    internal var confirmation by mutableStateOf<Confirmation?>(null)
    internal data class Confirmation(val title: String, val message: String, val action: String, val confirm: () -> Unit)
    internal var packs by mutableStateOf<List<ModelPack>>(emptyList())
        private set
    internal var selection by mutableStateOf<Pair<ModelPack, PackVoice>?>(null)
        private set
    internal var currentPack by mutableStateOf<ModelPack?>(null)
        private set
    internal var parameters by mutableStateOf<List<String>>(emptyList())
    internal var speed by mutableFloatStateOf(1f)
        private set
    internal var snapshot by mutableStateOf(ReaderPlaybackService.Snapshot(ReaderPlaybackService.State.IDLE))
        private set
    internal var notice by mutableStateOf("")
        private set
    internal var noticeError by mutableStateOf(false)
        private set
    internal var busy by mutableStateOf(false)
        private set
    internal var documentReady by mutableStateOf(false)
        private set
    internal var documentInfo by mutableStateOf("")
        private set
    internal var recordingDialog by mutableStateOf<VoiceRecordingDialog?>(null)
        private set
    internal lateinit var editor: EditText
        private set
    internal var editing by mutableStateOf(false)
        private set
    private var editorKeyListener: android.text.method.KeyListener? = null
    private var editorInputType = 0
    internal lateinit var readingHighlight: ReadingHighlight
        private set
    internal lateinit var preview: SamplePreview
        private set
    internal var previewExpanded by mutableStateOf(false)
    private var normalUi = false
    private var recordingPackId: String? = null
    private var importPackId: String? = null
    private var videoPackId: String? = null
    internal var videoDialog by mutableStateOf<VideoImportDialog?>(null)
        private set
    private val videoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val pack = videoPackId
        videoPackId = null
        if (uri != null && pack != null) showVideo(pack, uri) else sheet = "add"
    }
    private var pendingExport: Uri? = null
    private var cursorToRestore = 0
    private var scrollToRestore = 0
    private val main = Handler(Looper.getMainLooper())
    private val persistDraft = Runnable { saveDraft() }
    private var readerService: ReaderPlaybackService? = null
    private var readerBound = false
    private val readerListener = object : ReaderPlaybackService.Listener {
        override fun onReaderStateChanged(value: ReaderPlaybackService.Snapshot) { snapshot = value }
    }
    private val readerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            readerService = (binder as? ReaderPlaybackService.LocalBinder)?.service()
            readerService?.addListener(readerListener)
            pendingExport?.let { readerService?.export(it); pendingExport = null }
        }
        override fun onServiceDisconnected(name: ComponentName?) { readerService = null }
    }
    private val prefs get() = getSharedPreferences(READER_PREFS, MODE_PRIVATE)

    override fun onCreate(state: Bundle?) {
        appearance = prefs.getString(APPEARANCE, APPEARANCE_SYSTEM) ?: APPEARANCE_SYSTEM
        setTheme(themeFor(appearance))
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        preview = SamplePreview(this)
        if (intent?.action == TextToSpeech.Engine.ACTION_CHECK_TTS_DATA) {
            thread(name = "pockettts-check-data") {
                val voices = runCatching { ModelPackRepository.list(this).map { it.languageTag }.distinct() }
                runOnUiThread {
                    setResult(if (voices.isSuccess) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
                        Intent().putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(voices.getOrDefault(emptyList())))
                            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, if (voices.isSuccess) arrayListOf() else arrayListOf("en-US")))
                    finish()
                }
            }
            return
        }
        if (intent?.action == ACTION_SELF_TEST || intent?.action == ACTION_MULTI_VOICE_SELF_TEST) {
            var result by mutableStateOf(getString(R.string.self_test_running))
            setContent { PocketTheme(appearance) { DiagnosticScreen(result) } }
            thread(name = "pockettts-self-test") {
                runCatching {
                    if (intent.action == ACTION_SELF_TEST) {
                        val value = runSelfTest()
                        Log.i(TAG, "SELF_TEST_OK file=${value.file} samples=${value.samples}")
                        getString(R.string.self_test_success, value.samples, value.samples / SAMPLE_RATE)
                    } else {
                        val value = runMultiVoiceSelfTest()
                        Log.i(TAG, "MULTI_VOICE_TEST_OK $value")
                        getString(R.string.multi_voice_test_success)
                    }
                }.onSuccess { value -> runOnUiThread { result = value } }
                    .onFailure { error ->
                        Log.e(TAG, "SELF_TEST_FAILED", error)
                        runOnUiThread { result = getString(R.string.self_test_failed, error.message.orEmpty()) }
                    }
            }
            return
        }
        normalUi = true
        tab = state?.getInt("tab", 0)?.coerceIn(0, 2) ?: 0
        recordingPackId = state?.getString("recordingPack")
        importPackId = state?.getString("importPack")
        videoPackId = state?.getString("videoPack")
        pendingExport = state?.getString("pendingExport")?.let(Uri::parse)
        cursorToRestore = state?.getInt("cursor", 0) ?: 0
        scrollToRestore = state?.getInt("editorScroll", 0) ?: 0
        speed = PlaybackSpeed.read(this)
        editor = EditText(this).apply {
            hint = getString(R.string.reader_hint)
            contentDescription = getString(R.string.ui_document)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            textSize = 18f
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSaveEnabled = false // Keep large documents out of Binder instance-state parcels.
            background = null
            @Suppress("ClickableViewAccessibility") // Never consumes touch; native TextView handles clicks.
            setOnTouchListener { view, event ->
                // Keep the compact Reader's outer scroller from stealing native text drags.
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(
                        view.canScrollVertically(-1) || view.canScrollVertically(1))
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // TextView still owns scrolling, clicks and native editing/selection.
            }
            accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                override fun performAccessibilityAction(host: android.view.View, action: Int, args: Bundle?): Boolean {
                    if (!editing && action in listOf(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CUT,
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY,
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)) return false
                    return super.performAccessibilityAction(host, action, args)
                }
                override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (!editing) {
                        info.isEditable = false; info.className = "android.widget.TextView"
                        listOf(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT,
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION,
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT,
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY,
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE).forEach { info.removeAction(it) }
                    }
                }
            }
            setPadding(dp(16), dp(12), dp(16), dp(16))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (::readingHighlight.isInitialized) readingHighlight.edited()
                    if (documentReady && snapshot.document != null && snapshot.state != ReaderPlaybackService.State.STOPPED) readerService?.stop()
                    main.removeCallbacks(persistDraft); main.postDelayed(persistDraft, 700)
                }
            })
        }
        readingHighlight = ReadingHighlight(editor)
        editorKeyListener = editor.keyListener
        editorInputType = editor.inputType
        setEditing(state?.getBoolean("editing") ?: false, focus = false)
        busy = true; notice = getString(R.string.bundled_preparing)
        setContent { PocketTheme(appearance) { PocketApp(this) } }
        loadDraft()
    }
    override fun onStart() {
        super.onStart()
        if (normalUi && !readerBound) readerBound = bindService(Intent(this, ReaderPlaybackService::class.java), readerConnection, Context.BIND_AUTO_CREATE)
        else readerService?.addListener(readerListener)
    }
    override fun onStop() {
        if (normalUi) saveDraft()
        recordingDialog?.close()
        videoDialog?.close()
        preview.close()
        readerService?.removeListener(readerListener)
        super.onStop()
    }
    override fun onDestroy() {
        main.removeCallbacks(persistDraft)
        if (readerBound) { readerService?.removeListener(readerListener); unbindService(readerConnection) }
        readerService = null
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("tab", tab)
        out.putBoolean("editing", editing)
        out.putString("recordingPack", recordingPackId)
        out.putString("importPack", importPackId)
        out.putString("videoPack", videoPackId)
        out.putString("pendingExport", pendingExport?.toString())
        if (normalUi) { out.putInt("cursor", editor.selectionStart); out.putInt("editorScroll", editor.scrollY) }
        super.onSaveInstanceState(out)
    }
    internal fun switchTab(value: Int) { hideKeyboard(); preview.close(); tab = value }
    internal fun setAppearance(value: String) {
        setTheme(themeFor(value))
        appearance = value
        prefs.edit().putString(APPEARANCE, value).apply()
    }
    internal fun selectSpeed(value: Float) {
        speed = PlaybackSpeed.normalize(value)
        PlaybackSpeed.save(this, speed)
        readerService?.setSpeed(speed)
        preview.changeSpeed(speed)
    }
    internal fun selectVoice(pack: ModelPack, voice: PackVoice) {
        if (selection?.first?.id != pack.id || selection?.second?.id != voice.id) readerService?.stop()
        ModelPackRepository.selectPack(this, pack.id)
        ModelPackRepository.selectVoice(this, pack.id, voice.id)
        refreshData()
    }
    internal fun selectPack(pack: ModelPack) {
        if (currentPack?.id != pack.id) readerService?.stop()
        ModelPackRepository.selectPack(this, pack.id); refreshData()
    }
    private fun refreshData(message: String? = null, error: Boolean = false) {
        packs = ModelPackRepository.list(this)
        currentPack = ModelPackRepository.selectedPack(this)
        selection = ModelPackRepository.resolveVoice(this, null)
        parameters = currentPack?.let { listOf(ModelPackRepository.temperature(this, it).toString(),
            ModelPackRepository.lsdSteps(this, it).toString(), ModelPackRepository.threads(this, it).toString(),
            ModelPackRepository.sentencePauseMs(this, it).toString(), ModelPackRepository.maxTextTokens(this, it).toString()) } ?: emptyList()
        if (message != null) { notice = message; noticeError = error }
    }
    internal fun clearDocument() {
        if (!editing) return
        confirmation = Confirmation(getString(R.string.reader_clear), getString(R.string.reader_clear_confirm), getString(R.string.reader_clear)) { editor.setText("") }
    }
    internal fun startReaderPlayback() {
        val selected = selection ?: run { toast(R.string.status_no_model); return }
        val service = readerService ?: run { toast(R.string.reader_starting); return }
        // Snapshot the editable on the UI thread. Playback must not change editor focus
        // or start an IME/layout transition while a touch or composing edit is in flight.
        val text = editor.text.toString()
        if (snapshot.state != ReaderPlaybackService.State.PAUSED) readingHighlight.resetFollow()
        preview.close()
        service.play(text, selected.first.id, selected.second.id, speed)
    }
    internal fun pauseReader() { readerService?.pause() }
    internal fun stopReader() { readerService?.stop() }
    internal fun seekReader(position: Long) { readerService?.seekTo(position) }
    internal fun exportAudio() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/wav"; putExtra(Intent.EXTRA_TITLE, "Pocket TTS.wav")
        }, REQUEST_EXPORT)
    }
    private fun blocked(): Boolean {
        if (busy || readerService?.isBusy() == true) { toast(R.string.voice_stop_first); return true }
        return false
    }
    internal fun previewVoice(pack: ModelPack, voice: PackVoice, recordingVersion: Boolean? = null) {
        if (!blocked()) {
            if (preview.state.key == null) previewExpanded = false
            detail = null
            val source = recordingVersion?.let { ModelPackRepository.recordingSource(pack, voice, it) }
                ?: File(pack.voicesDir, voice.fileName)
            val title = voice.displayName + (recordingVersion?.let { " · " + getString(if (it) R.string.df_after else R.string.df_before) } ?: "")
            preview.play(source, title, source.absolutePath) {
                notice = getString(R.string.reader_error_output); noticeError = true
            }
        }
    }
    internal fun stopPreview() { previewExpanded = false; preview.close() }
    internal fun deleteVoice(pack: ModelPack, voice: PackVoice) {
        if (blocked() || !voice.userCreated) return
        confirmation = Confirmation(getString(R.string.voice_delete_title, voice.displayName),
            getString(R.string.voice_delete_message), getString(R.string.voice_delete)) {
            if (!blocked()) {
                preview.close(); detail = null; busy = true
                thread(name = "pockettts-delete-voice") {
                    val result = runCatching { ModelPackRepository.deleteVoice(applicationContext, pack.id, voice.id) }
                    runOnUiThread { busy = false; refreshData(if (result.isFailure) getString(R.string.voice_delete_failed) else null, result.isFailure) }
                }
            }
        }
    }
    internal fun addVoice(pack: ModelPack, record: Boolean) {
        if (blocked()) return
        sheet = null
        if (record) {
            recordingPackId = pack.id
            showRecorder()
        } else { importPackId = pack.id; openDocument(REQUEST_VOICE, "audio/wav") }
    }
    internal fun pickVideo(pack: ModelPack) {
        if (blocked()) return
        preview.close(); sheet = null; videoPackId = pack.id
        runCatching { videoPicker.launch(arrayOf("video/*")) }.onFailure {
            videoPackId = null
            Toast.makeText(this, "No system file picker is available.", Toast.LENGTH_LONG).show()
        }
    }
    internal fun showVideo(packId: String, uri: Uri) {
        if (blocked()) return
        preview.close(); recordingDialog?.close(); videoDialog?.close(); sheet = null
        videoDialog = VideoImportDialog(this, uri, { file ->
            recordingDialog = VoiceRecordingDialog(this, packId,
                { ModelPackRepository.selectPack(this, packId); refreshData(); tab = 1 },
                { recordingDialog = null }, {}, extracted = file)
        }, { videoDialog = null }, { packs.firstOrNull { it.id == packId }?.let(::pickVideo) })
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            recordingDialog?.onPermissionResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }
    private fun showRecorder() {
        val packId = recordingPackId ?: return
        preview.close()
        recordingDialog = VoiceRecordingDialog(this, packId,
            { ModelPackRepository.selectPack(this, packId); refreshData(); tab = 1 }, { recordingDialog = null },
            { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC) })
    }
    internal fun saveSettings() {
        if (blocked()) return
        val pack = currentPack ?: return
        val temperature = parameters[0].replace(',', '.').toFloatOrNull()
        val lsd = parameters[1].toIntOrNull()
        val threads = parameters[2].toIntOrNull()
        val pause = parameters[3].toIntOrNull()
        val tokens = parameters[4].toIntOrNull()
        if (temperature == null || temperature !in 0f..2f || lsd == null || lsd !in 1..8 ||
            threads == null || threads !in 1..8 || pause == null || pause !in 0..2000 || tokens == null || tokens !in 10..200) {
            notice = getString(R.string.invalid_parameters); noticeError = true; return
        }
        ModelPackRepository.saveParameters(this, pack, temperature, lsd, threads, pause, tokens)
        notice = getString(R.string.status_saved, pack.displayName); noticeError = false
        sheet = null
    }
    internal fun openTtsSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            .onFailure { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
    internal fun showAbout() { sheet = "about" }
    internal fun setEditing(value: Boolean, focus: Boolean = true) {
        if (value && !editing) readerService?.stop()
        val start = editor.selectionStart.coerceIn(0, editor.length())
        val end = editor.selectionEnd.coerceIn(0, editor.length())
        val scroll = editor.scrollY
        editing = value
        editor.keyListener = if (value) editorKeyListener else null
        editor.setRawInputType(if (value) editorInputType else android.text.InputType.TYPE_NULL)
        editor.movementMethod = if (value) android.text.method.ArrowKeyMovementMethod.getInstance() else android.text.method.ScrollingMovementMethod.getInstance()
        editor.isCursorVisible = value
        editor.isFocusable = value; editor.isFocusableInTouchMode = value
        editor.isLongClickable = value
        editor.showSoftInputOnFocus = value
        if (value) editor.setSelection(start, end) else { android.text.Selection.removeSelection(editor.text); hideKeyboard() }
        editor.scrollTo(0, scroll)
        if (value && focus) {
            editor.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private fun openDocument(requestCode: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = mime
            if (requestCode == REQUEST_VOICE) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
        }, requestCode)
    }
    @Deprecated("Existing document-picker contract retained")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == REQUEST_EXPORT) {
            if (readerService != null) readerService?.export(uri) else pendingExport = uri
            return
        }
        if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) { notice = getString(R.string.error_untrusted_document); noticeError = true; return }
        if (requestCode == REQUEST_VOICE) {
            val packId = importPackId ?: return
            showImportedVoice(packId, uri)
            return
        }
    }
    internal fun showImportedVoice(packId: String, uri: Uri) {
        preview.close()
        recordingDialog?.close()
        recordingDialog = VoiceRecordingDialog(this, packId,
            { ModelPackRepository.selectPack(this, packId); refreshData(); tab = 1 },
            { recordingDialog = null }, {}, imported = uri,
            chooseAnother = { packs.firstOrNull { it.id == packId }?.let { addVoice(it, false) } })
    }
    private fun hideKeyboard() {
        if (!normalUi) return
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
    }
    private fun loadDraft() {
        draftWorker.execute {
            val result = runCatching {
                BundledPocketTts.ensure(this)
                ModelPackRepository.applySystemDefaultSelection(this)
                val file = File(filesDir, "reader-document.txt")
                if (file.exists()) android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                else prefs.getString(READER_DOCUMENT, "").orEmpty()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    busy = false
                    if (result.isSuccess) refreshData("") else {
                        notice = getString(R.string.bundled_failed, result.exceptionOrNull()?.message.orEmpty()); noticeError = true
                    }
                    editor.setText(result.getOrDefault(""))
                    readingHighlight.resetFollow()
                    if (editing) editor.setSelection(cursorToRestore.coerceIn(0, editor.length()))
                    editor.post { editor.scrollTo(0, scrollToRestore) }
                    documentReady = result.isSuccess
                    if (result.isFailure) toast(R.string.reader_draft_failed)
                    saveDraft()
                }
            }
        }
    }
    private fun saveDraft() {
        if (!documentReady || !normalUi) return
        main.removeCallbacks(persistDraft)
        val text = editor.text.toString()
        draftWorker.execute {
            var words = 0; var inside = false
            text.forEach { char -> if (char.isWhitespace()) inside = false else if (!inside) { words++; inside = true } }
            val result = runCatching {
                val atomic = android.util.AtomicFile(File(filesDir, "reader-document.txt"))
                val output = atomic.startWrite()
                try { output.write(text.toByteArray()); atomic.finishWrite(output) }
                catch (error: Throwable) { atomic.failWrite(output); throw error }
                prefs.edit().remove(READER_DOCUMENT).apply()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    documentInfo = getString(R.string.reader_document_info, words, text.length)
                    if (result.isFailure) toast(R.string.reader_draft_failed)
                }
            }
        }
    }
    private fun themeFor(value: String?): Int = when (value) {
        APPEARANCE_LIGHT -> R.style.AppTheme_Light
        APPEARANCE_DARK -> R.style.AppTheme_Dark
        APPEARANCE_AMOLED -> R.style.AppTheme_Amoled
        else -> if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) R.style.AppTheme_Dark else R.style.AppTheme_Light
    }
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun runMultiVoiceSelfTest(): MultiVoiceTestResult {
        val pack = ModelPackRepository.list(this).firstOrNull { it.voices.size >= 2 }
            ?: error(getString(R.string.error_need_two_voices))
        val first = pack.voices[0]
        val second = pack.voices[1]
        NativePocketTts(
            pack.modelsDir.absolutePath,
            pack.voicesDir.absolutePath,
            pack.precision,
            0f,
            ModelPackRepository.lsdSteps(this, pack),
            1,
            ModelPackRepository.sentencePauseMs(this, pack),
            ModelPackRepository.maxTextTokens(this, pack)
        ).use { tts ->
            val firstHash = synthesizeFingerprint(tts, first.fileName)
            val secondHash = synthesizeFingerprint(tts, second.fileName)
            val repeatHash = synthesizeFingerprint(tts, first.fileName)
            check(firstHash == repeatHash) { getString(R.string.error_voice_repeat_mismatch) }
            check(firstHash != secondHash) { getString(R.string.error_voices_not_distinct) }
            return MultiVoiceTestResult(pack.id, first.id, second.id, firstHash, secondHash, repeatHash)
        }
    }

    private fun synthesizeFingerprint(tts: NativePocketTts, voiceFile: String): String {
        var hash = -0x340d631b7bdddcdbL
        var samples = 0L
        check(tts.synthesize(getString(R.string.multi_voice_test_text), voiceFile, object : NativePocketTts.AudioSink {
            override fun onAudio(values: FloatArray): Boolean {
                values.forEach { value ->
                    hash = hash xor value.toBits().toLong()
                    hash *= 0x100000001b3L
                }
                samples += values.size
                return true
            }
        })) { getString(R.string.error_native_synthesis) }
        check(samples > 0) { getString(R.string.error_no_samples) }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    private fun runSelfTest(): SelfTestResult {
        val (pack, voice) = ModelPackRepository.resolveVoice(this, null) ?: error(getString(R.string.error_no_model_voice))
        val pcm = ByteArrayOutputStream()
        var samples = 0L
        var chunks = 0
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        NativePocketTts(
            pack.modelsDir.absolutePath,
            pack.voicesDir.absolutePath,
            pack.precision,
            ModelPackRepository.temperature(this, pack),
            ModelPackRepository.lsdSteps(this, pack),
            ModelPackRepository.threads(this, pack),
            ModelPackRepository.sentencePauseMs(this, pack),
            ModelPackRepository.maxTextTokens(this, pack)
        ).use { tts ->
            check(tts.synthesize(getString(R.string.test_text), voice.fileName, object : NativePocketTts.AudioSink {
                override fun onAudio(values: FloatArray): Boolean {
                    chunks++
                    samples += values.size
                    val bytes = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    values.forEach { value ->
                        minimum = minOf(minimum, value)
                        maximum = maxOf(maximum, value)
                        bytes.putShort((value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    }
                    pcm.write(bytes.array())
                    Log.i(TAG, "SELF_TEST_CHUNK index=$chunks samples=${values.size} total=$samples")
                    return true
                }
            })) { getString(R.string.error_native_synthesis) }
        }
        check(samples > 0) { getString(R.string.error_no_samples) }
        val output = File(filesDir, "selftest.wav")
        val audio = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + audio.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(audio.size)
        }.array()
        output.outputStream().use { it.write(header); it.write(audio) }
        return SelfTestResult(output.absolutePath, samples, chunks, minimum, maximum)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SelfTestResult(val file: String, val samples: Long, val chunks: Int, val min: Float, val max: Float)
    private data class MultiVoiceTestResult(
        val packId: String,
        val firstVoice: String,
        val secondVoice: String,
        val firstHash: String,
        val secondHash: String,
        val repeatHash: String
    )

    private companion object {
        val draftWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
        const val TAG = "PocketTTS"
        const val ACTION_SELF_TEST = "org.pockettts.android.engine.SELF_TEST"
        const val ACTION_MULTI_VOICE_SELF_TEST = "org.pockettts.android.engine.MULTI_VOICE_SELF_TEST"
        const val REQUEST_EXPORT = 1003
        const val REQUEST_MIC = 1004
        const val REQUEST_VOICE = 1002
        const val SAMPLE_RATE = 24_000
        const val READER_PREFS = "pockettts_reader"
        const val READER_DOCUMENT = "document"
        const val APPEARANCE = "appearance"
        const val APPEARANCE_SYSTEM = "system"
        const val APPEARANCE_LIGHT = "light"
        const val APPEARANCE_DARK = "dark"
        const val APPEARANCE_AMOLED = "amoled"
    }
}
