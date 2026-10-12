package org.pockettts.android.engine

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** All controls use the owning SamplePreview, never an independent playback flag. */
@Composable
internal fun SampleTransport(player: SamplePreview, enabled: Boolean = true, active: Boolean = player.state.key != null,
    position: Int = if (active) player.state.positionMs else 0, duration: Int = if (active) player.state.durationMs else 0,
    prefix: String = "preview", playTag: String = "$prefix-toggle", start: () -> Unit = player::toggle,
    seek: (Int) -> Unit = player::seekWithFeedback, stop: () -> Unit = player::close) {
    val state = player.state
    val playing = active && state.playing
    val label = stringResource(if (playing) R.string.reader_pause else R.string.reader_play)
    val stopLabel = stringResource(R.string.reader_stop)
    val backLabel = stringResource(R.string.preview_back_five)
    val forwardLabel = stringResource(R.string.preview_forward_five)
    Surface(Modifier.fillMaxWidth().testTag("$prefix-platform"), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        border = BorderStroke(.75.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        FilledTonalIconButton(onClick = { seek((position.toLong() - 5000).coerceIn(0, duration.toLong()).toInt()) },
            enabled = enabled && duration > 0 && !(active && state.loading),
            modifier = Modifier.size(48.dp).testTag("$prefix-back").semantics { contentDescription = backLabel }) {
            Text("−5s", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        FilledIconButton(onClick = { if (active) player.toggle() else start() }, enabled = enabled && !(active && state.loading),
            modifier = Modifier.size(48.dp).testTag(playTag).semantics { contentDescription = label }) {
            val tint = LocalContentColor.current
            Crossfade(playing, animationSpec = tween(if (LocalStudioMotion.current) 90 else 0), label = "sample-play-pause") { pausedIcon ->
                Canvas(Modifier.size(26.dp)) {
                    if (pausedIcon) {
                        drawRect(tint, Offset(size.width * .2f, size.height * .12f), Size(size.width * .2f, size.height * .76f))
                        drawRect(tint, Offset(size.width * .6f, size.height * .12f), Size(size.width * .2f, size.height * .76f))
                    } else drawPath(Path().apply { moveTo(size.width * .25f, size.height * .1f); lineTo(size.width * .85f, size.height * .5f); lineTo(size.width * .25f, size.height * .9f); close() }, tint)
                }
            }
        }
        FilledTonalIconButton(onClick = { seek((position.toLong() + 5000).coerceIn(0, duration.toLong()).toInt()) },
            enabled = enabled && duration > 0 && !(active && state.loading),
            modifier = Modifier.size(48.dp).testTag("$prefix-forward").semantics { contentDescription = forwardLabel }) {
            Text("+5s", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        FilledTonalIconButton(onClick = { player.stopFromControl(stop) }, enabled = enabled && active,
            modifier = Modifier.size(48.dp).testTag("$prefix-stop").semantics { contentDescription = stopLabel }) {
            val tint = LocalContentColor.current
            Canvas(Modifier.size(18.dp)) { drawRect(tint) }
        }
    }
    }
}
