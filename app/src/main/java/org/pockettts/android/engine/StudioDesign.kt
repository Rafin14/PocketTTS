@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.pockettts.android.engine

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.FilledTonalButton as MaterialTonalButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RangeSlider as MaterialRangeSlider

internal object StudioTokens {
    val Accent = Color(0xFF485BD0)
    val Azure = Color(0xFF477AE8)
    val Violet = Color(0xFF765ACD)
    val ButtonRadius = RoundedCornerShape(14.dp)
    val CardRadius = RoundedCornerShape(24.dp)
    val PageInset = 20.dp
    val SheetInset = 24.dp
    val ControlGap = 12.dp
    val ActionGap = 10.dp
    val SectionGap = 18.dp
    val SurfaceDepth = 1.dp
    val SelectedDepth = 3.dp
    val DockDepth = 4.dp
    val VoiceTones = listOf(Accent, Violet, Color(0xFF307890), Color(0xFF986129))
    const val ColorMillis = 160
    const val NavigationMillis = 140
    const val PressStiffness = 900f
}
internal val LocalStudioMotion = staticCompositionLocalOf { true }
internal val LocalStudioContrast = staticCompositionLocalOf { false }

/** Animate only the incoming destination. Never duplicate or crossfade the native editor. */
@Composable
internal fun StudioDestination(destination: Int, content: @Composable () -> Unit) {
    val reveal = remember { Animatable(1f) }
    val motion = LocalStudioMotion.current
    LaunchedEffect(destination, motion) {
        if (motion) { reveal.snapTo(.88f); reveal.animateTo(1f, tween(StudioTokens.NavigationMillis)) }
        else reveal.snapTo(1f)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }, contentAlignment = Alignment.TopStart) { content() }
}

/** Observe live Android preferences; do not cache animation settings for the whole process. */
@Composable
internal fun StudioPreferences(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var motion by remember { mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) }
    var contrast by remember { mutableStateOf(Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                motion = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
                contrast = Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalStudioMotion provides motion, LocalStudioContrast provides contrast, content = content)
}

@Composable
private fun Modifier.pressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduced = !LocalStudioMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) .98f else 1f,
        spring(dampingRatio = 1f, stiffness = StudioTokens.PressStiffness), label = "press-response")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun StudioButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = StudioTokens.ButtonRadius,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = StudioTokens.Accent, contentColor = Color.White),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(defaultElevation = StudioTokens.SurfaceDepth, pressedElevation = 0.dp),
    border: BorderStroke? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    // Material retains a 48 dp hit region around the smaller painted surface.
    MaterialButton(onClick, modifier.pressFeedback(interaction).defaultMinSize(minHeight = 44.dp), enabled,
        shape, colors,
        elevation, border ?: BorderStroke(.75.dp, Color.White.copy(alpha = if (enabled) .18f else .04f)),
        contentPadding, interaction, content)
}

@Composable
internal fun StudioTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = StudioTokens.ButtonRadius, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    MaterialTonalButton(onClick, modifier.pressFeedback(interaction).defaultMinSize(minHeight = 40.dp), enabled, shape, colors,
        elevation, border ?: BorderStroke(.75.dp, LocalGlass.current.border), contentPadding, interaction, content)
}

@Composable
internal fun StudioOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = StudioTokens.ButtonRadius, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    MaterialOutlinedButton(onClick, modifier.pressFeedback(interaction).defaultMinSize(minHeight = 40.dp), enabled, shape, colors,
        elevation, border ?: BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else LocalGlass.current.border),
        contentPadding, interaction, content)
}

@Composable
internal fun StudioSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    track: @Composable (SliderState) -> Unit = { SliderDefaults.Track(it, Modifier.height(5.dp), colors = colors, enabled = enabled) }) {
    MaterialSlider(value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled, onValueChangeFinished = onValueChangeFinished, colors = colors,
        interactionSource = interactionSource, steps = steps, valueRange = valueRange,
        thumb = { Box(Modifier.size(22.dp).shadow(if (enabled) 3.dp else 0.dp, CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
            .border(5.dp, if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant, CircleShape)) }, track = track)
}

@Composable
internal fun StudioRangeSlider(value: ClosedFloatingPointRange<Float>, onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null) {
    val colors = SliderDefaults.colors()
    MaterialRangeSlider(value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled, valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
        startThumb = { StudioTrimThumb(enabled) }, endThumb = { StudioTrimThumb(enabled) },
        track = { SliderDefaults.Track(it, Modifier.height(5.dp), colors = colors, enabled = enabled) })
}

@Composable
private fun StudioTrimThumb(enabled: Boolean) {
    Box(Modifier.width(8.dp).height(32.dp).shadow(3.dp,RoundedCornerShape(4.dp))
        .background(if(enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp))
        .border(1.dp,MaterialTheme.colorScheme.surface,RoundedCornerShape(4.dp)))
}

@Composable
internal fun StudioSpeedPresets(speed: Float, change: (Float) -> Unit) {
    val values=listOf(.75f,1f,1.25f,1.5f)
    val perRow=if(LocalDensity.current.fontScale>1.3f) 2 else 4
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        values.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp)).padding(4.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                row.forEach { value ->
                    val selected=kotlin.math.abs(speed-value)<.001f
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if(selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(selected,role=Role.RadioButton,onClick={ change(value) })
                        .heightIn(min=48.dp).padding(horizontal=4.dp,vertical=12.dp).testTag("speed-preset-$value"),contentAlignment=Alignment.Center) {
                        Text(PlaybackSpeed.label(value),style=MaterialTheme.typography.labelLarge,
                            color=if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A static composition, cached by size/theme. Never animate the entire backdrop. */
@Composable
internal fun StudioBackground(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val contrast = LocalStudioContrast.current
    val amoled = colors.background == Color.Black
    Box(Modifier.fillMaxSize().background(colors.background).drawWithCache {
        val blue = Brush.radialGradient(listOf(colors.primary.copy(alpha = if (amoled || contrast) 0f else .13f), Color.Transparent),
            Offset(size.width * .95f, size.height * .03f), size.width * .95f)
        val violet = Brush.radialGradient(listOf(StudioTokens.Violet.copy(alpha = if (amoled || contrast) 0f else .09f), Color.Transparent),
            Offset(size.width * .04f, size.height * .62f), size.width * .8f)
        onDrawBehind { drawRect(blue); drawRect(violet) }
    }, content = content)
}

@Composable
internal fun StudioEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier.size(32.dp).background(Brush.linearGradient(listOf(StudioTokens.Azure, StudioTokens.Violet)), RoundedCornerShape(10.dp))) {
        for (index in 0..4) {
            val height = listOf(.24f,.45f,.68f,.45f,.24f)[index]*size.height
            val x = size.width*(.24f+index*.13f)
            drawLine(Color.White, Offset(x,(size.height-height)/2), Offset(x,(size.height+height)/2), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun VoiceIdentity(name: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val initial = remember(name) {
        val text = name.trim()
        if (text.isEmpty()) "♪" else android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).run {
            setText(text); first(); text.substring(0, next()).uppercase(java.util.Locale.ROOT)
        }
    }
    val tone = StudioTokens.VoiceTones[Math.floorMod(name.hashCode(), StudioTokens.VoiceTones.size)]
    Box(modifier.clearAndSetSemantics { }.size(if (compact) 40.dp else 56.dp)
        .shadow(4.dp, RoundedCornerShape(if (compact) 14.dp else 19.dp))
        .background(Brush.linearGradient(listOf(tone, tone.copy(red=(tone.red+.13f).coerceAtMost(1f)))), RoundedCornerShape(if (compact) 14.dp else 19.dp))
        .border(.75.dp, Color.White.copy(alpha=.25f), RoundedCornerShape(if (compact) 14.dp else 19.dp)), contentAlignment=Alignment.Center) {
        Text(initial, color=Color.White,
            style=if(compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun StudioBadge(text: String, active: Boolean = false) {
    val color = if (active) LocalGlass.current.success else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color=color.copy(alpha=.09f), shape=RoundedCornerShape(50), border=BorderStroke(.75.dp,color.copy(alpha=.2f))) {
        Text(text, Modifier.padding(horizontal=10.dp,vertical=5.dp), style=MaterialTheme.typography.labelSmall, color=color)
    }
}

@Composable
internal fun StudioTabBar(selected: Int, change: (Int) -> Unit, feedback: AppHaptics? = null) {
    val shape=RoundedCornerShape(26.dp)
    val iconOnly = LocalDensity.current.fontScale > 1.3f
    val motion = LocalStudioMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentChange by rememberUpdatedState(change)
    val currentSelected by rememberUpdatedState(selected)
    var dragging by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Int?>(null) }
    val labels = listOf(R.string.tab_reader,R.string.tab_voices,R.string.tab_settings).map { stringResource(it) }
    val dragDescription = if (dragging) preview?.let { stringResource(R.string.nav_drag_preview, labels[it]) }
        ?: stringResource(R.string.nav_drag_cancel) else stringResource(R.string.nav_drag_hint)
    DisposableEffect(Unit) { onDispose { dragging = false; preview = null } }
    Surface(Modifier.fillMaxWidth().padding(horizontal=StudioTokens.PageInset,vertical=8.dp), color=LocalGlass.current.card,
        shape=shape, border=BorderStroke(if (dragging) 1.dp else .75.dp,
            if (dragging) MaterialTheme.colorScheme.primary.copy(alpha=.6f) else LocalGlass.current.border), shadowElevation=StudioTokens.DockDepth) {
        BoxWithConstraints(Modifier.testTag("navigation-panel")
            .semantics { stateDescription = dragDescription }
            .pointerInput(rtl) {
                var position = Offset.Zero
                fun update() {
                    val target = FeedbackGate.destination(position.x, position.y, size.width, size.height, rtl)
                    if (target != preview) { preview = target; if (target != null) feedback?.tick() }
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { position = it; dragging = true; update() },
                    onDrag = { pointer, _ -> pointer.consume(); position = pointer.position; update() },
                    onDragCancel = { dragging = false; preview = null },
                    onDragEnd = {
                        val target = preview
                        dragging = false; preview = null
                        if (target != null && target != currentSelected) { currentChange(target); feedback?.confirm() }
                    })
            }) {
        val cell = (maxWidth - 20.dp) / 3
        val committed by animateFloatAsState(selected.toFloat(), if (motion) spring(dampingRatio=1f,stiffness=650f) else tween(0), label="tab-settle")
        val hover by animateFloatAsState((preview ?: selected).toFloat(), if (motion) spring(dampingRatio=1f,stiffness=900f) else tween(0), label="tab-preview")
        Box(Modifier.matchParentSize().padding(6.dp)) {
            Box(Modifier.offset(x=(cell+4.dp)*committed).width(cell).fillMaxHeight()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)))
            if (dragging) Box(Modifier.offset(x=(cell+4.dp)*hover).width(cell).fillMaxHeight()
                .border(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.65f),RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha=.08f),RoundedCornerShape(24.dp)))
        }
        Row(Modifier.padding(6.dp), horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf(R.string.tab_reader,R.string.tab_voices,R.string.tab_settings).forEachIndexed { index, label ->
                val name = stringResource(label)
                val interaction=remember { MutableInteractionSource() }
                val foreground=if(index==selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).pressFeedback(interaction).clip(RoundedCornerShape(24.dp))
                    .selectable(index==selected,interactionSource=interaction,indication=LocalIndication.current,role=Role.Tab,onClick={
                        val changed = index != currentSelected
                        currentChange(index)
                        if (changed) feedback?.tick()
                    })
                    .heightIn(min=52.dp).padding(horizontal=4.dp,vertical=8.dp).testTag("nav-$index")
                    .semantics { if (iconOnly) contentDescription = name },
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Icon(listOf(Icons.Default.Edit,Icons.Default.Person,Icons.Default.Settings)[index],null,Modifier.size(21.dp),tint=foreground)
                    if (!iconOnly) Text(name,style=MaterialTheme.typography.labelSmall,color=foreground)
                }
            }
        }
        }
    }
}

