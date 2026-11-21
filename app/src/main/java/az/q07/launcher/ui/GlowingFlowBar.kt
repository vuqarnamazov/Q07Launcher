package az.q07.launcher.ui

import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppSettings
import kotlin.math.abs

/**
 * Menyu elementi.
 * @param glow bu elementin öz neon rəngi (null → ümumi rəng)
 * @param playing true olanda ikonun yanında kiçik ekvalayzer animasiyası
 * @param hasDoubleTap true olanda iki dəfə toxunma funksiyası var
 */
data class CarMenuItem(
    val title: String,
    val icon: ImageVector? = null,
    val bitmap: ImageBitmap? = null,
    val subtitle: String? = null,
    val glow: Color? = null,
    val playing: Boolean = false,
    val hasDoubleTap: Boolean = false
)

val DefaultGlow = Color(0xFF00E5FF)

/** Neon kapsul: yumşaq qradient fon, neon çərçivə, kənar işıq kölgəsi, altda ağ fokus zolağı */
private fun Modifier.glowCapsule(glowColor: Color, cornerDp: Dp, intensity: Float): Modifier {
    val shape = RoundedCornerShape(cornerDp)
    return this
        .shadow(elevation = (18 * intensity).dp, shape = shape, ambientColor = glowColor, spotColor = glowColor)
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(glowColor.copy(alpha = 0.32f * intensity), glowColor.copy(alpha = 0.14f * intensity))
            )
        )
        .drawBehind {
            val corner = CornerRadius(cornerDp.toPx(), cornerDp.toPx())
            drawRoundRect(
                color = glowColor.copy(alpha = 0.9f * intensity),
                size = size,
                cornerRadius = corner,
                style = Stroke(width = 1.8.dp.toPx())
            )
            val barW = size.width * 0.45f
            val barLeft = (size.width - barW) / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f * intensity),
                topLeft = Offset(barLeft, size.height - 3.dp.toPx()),
                size = Size(barW, 3.dp.toPx()),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
}

/** Musiqi çalanda kiçik 3 zolaqlı ekvalayzer (yalnız çalanda kompozisiyada olur) */
@Composable
private fun Equalizer(color: Color) {
    val t = rememberInfiniteTransition(label = "eq")
    val b1 by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "eq1")
    val b2 by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "eq2")
    val b3 by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "eq3")
    Row(
        Modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(b1, b2, b3).forEach { v ->
            Box(Modifier.width(3.dp).fillMaxHeight(v).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

/** Toxunuş səsi + vibrasiya (Ayarlardan söndürülə bilər) */
@Composable
private fun rememberFeedback(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            if (AppSettings.haptics) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlowItem(
    item: CarMenuItem,
    index: Int,
    animatedIndex: Float,
    glowColor: Color,
    itemShape: RoundedCornerShape,
    modifier: Modifier,
    showTitle: Boolean,
    alignStart: Boolean,
    onPressed: (Int) -> Unit,
    onItemSelected: (Int) -> Unit,
    onItemLongClick: ((Int) -> Unit)?,
    onItemDoubleClick: ((Int) -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val feedback = rememberFeedback()

    // Toxunan an işıq dərhal bu elementə axır (klikin təsdiqini gözləmədən)
    LaunchedEffect(isPressed) { if (isPressed) onPressed(index) }

    val proximity = (1f - abs(animatedIndex - index)).coerceIn(0f, 1f)
    val isActive = proximity > 0.55f || isPressed
    val accent = item.glow ?: glowColor

    val contentColor by animateColorAsState(
        if (isActive) Color.White else Color.White.copy(alpha = 0.55f), tween(150), label = "textColor"
    )
    val iconTint by animateColorAsState(
        if (isActive) accent else Color.White.copy(alpha = 0.55f), tween(150), label = "iconTint"
    )
    val pressOffset by animateDpAsState(if (isPressed) 1.dp else 0.dp, tween(100), label = "pressFeedback")

    val doubleTap: (() -> Unit)? =
        if (item.hasDoubleTap && onItemDoubleClick != null) ({ feedback(); onItemDoubleClick(index) }) else null
    val longClick: (() -> Unit)? = onItemLongClick?.let { f -> { feedback(); f(index) } }

    Box(
        modifier = modifier
            .offset(y = pressOffset)
            .clip(itemShape)
            // Standart boz ripple söndürülüb — yalnız neon işıq görünür
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClick = longClick,
                // İki dəfə toxunma yalnız lazım olan elementdə — digərləri heç ləngimir
                onDoubleClick = doubleTap,
                onClick = { feedback(); onItemSelected(index) }
            )
            .padding(horizontal = 12.dp),
        contentAlignment = if (alignStart && showTitle) Alignment.CenterStart else Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val bmp = item.bitmap
            val vec = item.icon
            if (bmp != null) {
                Image(bmp, item.title, alpha = if (isActive) 1f else 0.75f, modifier = Modifier.size(38.dp))
            } else if (vec != null) {
                Icon(vec, item.title, tint = iconTint, modifier = Modifier.size(26.dp))
            }
            if (showTitle) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        text = item.title,
                        color = contentColor,
                        fontSize = 17.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = item.subtitle
                    if (sub != null) {
                        Text(
                            sub, color = contentColor.copy(alpha = 0.75f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (item.playing) Equalizer(accent)
        }
    }
}

/** Sürüşdürmə və basılma zamanı müvəqqəti hədəf */
private class FlowState {
    var dragIndex by mutableStateOf<Int?>(null)
    var pressIndex by mutableStateOf<Int?>(null)
}

@Composable
private fun rememberFlowState(selectedIndex: Int): FlowState {
    val state = remember { FlowState() }
    // Seçim təsdiqlənəndə müvəqqəti "basılma" hədəfini təmizlə
    LaunchedEffect(selectedIndex) { state.pressIndex = null }
    return state
}

@Composable
private fun rememberCapsuleColor(items: List<CarMenuItem>, target: Int, glowColor: Color): Color {
    val c by animateColorAsState(items.getOrNull(target)?.glow ?: glowColor, tween(250), label = "capsuleColor")
    return c
}

/**
 * Üfüqi neon menyu: işıq kapsulu düymələr arasında axaraq keçir (sliding glow + motion stretch).
 * Barmağı bar boyunca sürüşdürmək olar — buraxanda seçilir.
 */
@Composable
fun GlowingFlowNavigationBar(
    items: List<CarMenuItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: ((Int) -> Unit)? = null,
    onItemDoubleClick: ((Int) -> Unit)? = null,
    glowColor: Color = DefaultGlow,
    intensity: Float = 1f,
    barHeight: Dp = 68.dp
) {
    if (items.isEmpty()) return
    val state = rememberFlowState(selectedIndex)
    val target = state.dragIndex ?: state.pressIndex ?: selectedIndex
    val onSelect by rememberUpdatedState(onItemSelected)
    val feedback = rememberFeedback()

    val containerShape = RoundedCornerShape(20.dp)
    val itemShape = RoundedCornerShape(15.dp)

    val animatedIndex by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "lightFlowPosition"
    )
    val stretchFactor = (abs(target - animatedIndex) * 0.35f).coerceIn(0f, 0.45f)
    val capsuleColor = rememberCapsuleColor(items, target, glowColor)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(containerShape)
            .background(Color(0xFF0D1117).copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), containerShape)
            .padding(6.dp)
    ) {
        val density = LocalDensity.current
        val spacing = 8.dp
        val itemWidth = (maxWidth - spacing * (items.size - 1)) / items.size
        val showTitles = itemWidth >= 110.dp

        val itemWidthPx = with(density) { itemWidth.toPx() }
        val stepPx = itemWidthPx + with(density) { spacing.toPx() }
        val currentLeftPx = animatedIndex * stepPx
        val extraStretchPx = itemWidthPx * stretchFactor
        val indicatorLeftPx =
            if (target > animatedIndex) (currentLeftPx - extraStretchPx * 0.4f).coerceAtLeast(0f) else currentLeftPx

        if (target in items.indices) {
            Box(
                modifier = Modifier
                    .offset(x = with(density) { indicatorLeftPx.toDp() })
                    .width(with(density) { (itemWidthPx + extraStretchPx).toDp() })
                    .fillMaxHeight()
                    .glowCapsule(capsuleColor, 15.dp, intensity)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items.size, stepPx) {
                    fun idx(x: Float) = (x / stepPx).toInt().coerceIn(0, items.size - 1)
                    detectHorizontalDragGestures(
                        onDragStart = { o -> state.dragIndex = idx(o.x) },
                        onHorizontalDrag = { change, _ ->
                            val i = idx(change.position.x)
                            if (i != state.dragIndex) { state.dragIndex = i; feedback() }
                        },
                        onDragEnd = {
                            state.dragIndex?.let { onSelect(it) }
                            state.dragIndex = null; state.pressIndex = null
                        },
                        onDragCancel = { state.dragIndex = null; state.pressIndex = null }
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                GlowItem(
                    item, index, animatedIndex, capsuleColor, itemShape,
                    Modifier.weight(1f).fillMaxHeight(),
                    showTitle = showTitles, alignStart = false,
                    onPressed = { state.pressIndex = it },
                    onItemSelected = { state.pressIndex = null; onItemSelected(it) },
                    onItemLongClick = onItemLongClick?.let { f -> { i: Int -> state.pressIndex = null; f(i) } },
                    onItemDoubleClick = onItemDoubleClick?.let { f -> { i: Int -> state.pressIndex = null; f(i) } }
                )
            }
        }
    }
}

/**
 * Şaquli neon menyu (landscape dock və Ayarlar menyusu) — eyni axıcı işıq, Y oxu üzrə.
 */
@Composable
fun GlowingFlowNavigationColumn(
    items: List<CarMenuItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: ((Int) -> Unit)? = null,
    onItemDoubleClick: ((Int) -> Unit)? = null,
    glowColor: Color = DefaultGlow,
    intensity: Float = 1f,
    itemHeight: Dp = 68.dp,
    spacing: Dp = 8.dp
) {
    if (items.isEmpty()) return
    val state = rememberFlowState(selectedIndex)
    val target = state.dragIndex ?: state.pressIndex ?: selectedIndex
    val onSelect by rememberUpdatedState(onItemSelected)
    val feedback = rememberFeedback()

    val containerShape = RoundedCornerShape(20.dp)
    val itemShape = RoundedCornerShape(15.dp)

    val animatedIndex by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "lightFlowPositionY"
    )
    val stretchFactor = (abs(target - animatedIndex) * 0.35f).coerceIn(0f, 0.45f)
    val capsuleColor = rememberCapsuleColor(items, target, glowColor)

    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val stepPx = itemHeightPx + with(density) { spacing.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight * items.size + spacing * (items.size - 1) + 12.dp)
            .clip(containerShape)
            .background(Color(0xFF0D1117).copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), containerShape)
            .padding(6.dp)
    ) {
        val currentTopPx = animatedIndex * stepPx
        val extraStretchPx = itemHeightPx * stretchFactor
        val indicatorTopPx =
            if (target > animatedIndex) (currentTopPx - extraStretchPx * 0.4f).coerceAtLeast(0f) else currentTopPx

        if (target in items.indices) {
            Box(
                modifier = Modifier
                    .offset(y = with(density) { indicatorTopPx.toDp() })
                    .height(with(density) { (itemHeightPx + extraStretchPx).toDp() })
                    .fillMaxWidth()
                    .glowCapsule(capsuleColor, 15.dp, intensity)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(items.size, stepPx) {
                    fun idx(y: Float) = (y / stepPx).toInt().coerceIn(0, items.size - 1)
                    detectVerticalDragGestures(
                        onDragStart = { o -> state.dragIndex = idx(o.y) },
                        onVerticalDrag = { change, _ ->
                            val i = idx(change.position.y)
                            if (i != state.dragIndex) { state.dragIndex = i; feedback() }
                        },
                        onDragEnd = {
                            state.dragIndex?.let { onSelect(it) }
                            state.dragIndex = null; state.pressIndex = null
                        },
                        onDragCancel = { state.dragIndex = null; state.pressIndex = null }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            items.forEachIndexed { index, item ->
                GlowItem(
                    item, index, animatedIndex, capsuleColor, itemShape,
                    Modifier.fillMaxWidth().height(itemHeight),
                    showTitle = true, alignStart = true,
                    onPressed = { state.pressIndex = it },
                    onItemSelected = { state.pressIndex = null; onItemSelected(it) },
                    onItemLongClick = onItemLongClick?.let { f -> { i: Int -> state.pressIndex = null; f(i) } },
                    onItemDoubleClick = onItemDoubleClick?.let { f -> { i: Int -> state.pressIndex = null; f(i) } }
                )
            }
        }
    }
}
