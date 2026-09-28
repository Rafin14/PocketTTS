package org.pockettts.android.engine

import android.animation.ValueAnimator
import android.graphics.Rect
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.EditText

/** Playback offsets remain document offsets; scrolling uses the current rendered layout. */
internal class ReadingHighlight(private val editor: EditText) {
    private var source: String? = null
    private var matches = false
    private var span: BackgroundColorSpan? = null
    private var start = -1
    private var end = -1
    private var color = 0
    private var playing = false
    private var touching = false
    private var follow = true
    private var pending = false
    private var geometry: List<Int> = emptyList()
    private var animation: ValueAnimator? = null
    var reveal: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null
    var cancelReveal: (() -> Unit)? = null
    init {
        editor.viewTreeObserver.addOnPreDrawListener {
            val layout = editor.layout
            if (editor.isAttachedToWindow && playing && follow && !touching && start >= 0 && layout != null && end <= layout.text.length) {
                val current = listOf(editor.width, editor.height, layout.height,
                    layout.getLineTop(layout.getLineForOffset(start)), layout.getLineBottom(layout.getLineForOffset(end - 1)))
                if (pending || geometry != current) {
                    geometry = current; pending = false
                    keepVisible(current[3], current[4])
                }
            }
            true
        }
    }
    fun gestureStarted() { touching = true; follow = false; pending = false; cancelAnimation() }
    fun gestureEnded() { touching = false; if (pending) { follow = true; editor.invalidate() } }
    fun edited() { matches = false; clear() }
    fun resetFollow() { source = null; follow = true; pending = true }
    private fun cancelAnimation() { animation?.cancel(); animation = null; cancelReveal?.invoke() }
    private fun clear() {
        cancelAnimation(); span?.let { editor.text.removeSpan(it) }; span = null; start = -1; end = -1
    }
    fun update(snapshot: ReaderPlaybackService.Snapshot, tint: Int) {
        val resumed = !playing && snapshot.state == ReaderPlaybackService.State.PLAYING
        playing = snapshot.state == ReaderPlaybackService.State.PLAYING
        if (!playing) cancelAnimation()
        if (source !== snapshot.document) {
            source = snapshot.document
            matches = source != null && editor.text.toString() == source
        }
        val a = snapshot.rangeStart; val b = snapshot.rangeEnd
        if (!matches || a < 0 || b <= a || b > editor.length()) { clear(); return }
        val changed = start != a || end != b
        if (playing && (changed || resumed)) {
            pending = true
            if (!touching) follow = true
            editor.invalidate()
        }
        if (!changed && color == tint) return
        clear(); start = a; end = b; color = tint
        span = BackgroundColorSpan(tint).also { editor.text.setSpan(it, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    private fun keepVisible(lineTop: Int, lineBottom: Int) {
        val layout = editor.layout ?: return
        val height = editor.height - editor.totalPaddingTop - editor.totalPaddingBottom
        if (height <= 0) return
        val top = lineTop + editor.totalPaddingTop
        val bottom = minOf(lineBottom + editor.totalPaddingTop, top + height)
        val visible = Rect()
        val hasVisibleRect = editor.getLocalVisibleRect(visible)
        // getLocalVisibleRect uses scrolled content coordinates, not window coordinates.
        val visibleTop = maxOf(editor.scrollY + editor.totalPaddingTop, if (hasVisibleRect) visible.top else 0)
        val visibleBottom = minOf(editor.scrollY + editor.height - editor.totalPaddingBottom,
            if (hasVisibleRect) visible.bottom else Int.MAX_VALUE)
        val target = when {
            visibleBottom <= visibleTop || bottom - top > visibleBottom - visibleTop -> lineTop
            top < visibleTop -> editor.scrollY + top - visibleTop
            bottom > visibleBottom -> editor.scrollY + bottom - visibleBottom
            else -> editor.scrollY
        }.coerceIn(0, (layout.height - height).coerceAtLeast(0))
        cancelAnimation()
        fun revealInParent() {
            if (!playing || touching || !follow) return
            reveal?.invoke(androidx.compose.ui.geometry.Rect(0f, (top - editor.scrollY).toFloat(),
                editor.width.toFloat(), (bottom - editor.scrollY).toFloat()))
        }
        if (target == editor.scrollY) { revealInParent(); return }
        // Platform animation interpolates only scrolling, never the spoken-text position.
        animation = ValueAnimator.ofInt(editor.scrollY, target).apply {
            addUpdateListener { editor.scrollTo(editor.scrollX, it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) revealInParent() }
            })
            start()
        }
    }
}
