package az.q07.launcher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.ui.theme.CarColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * iPhone-dakı «slide to unlock» kimi: düyməni sağa sona qədər sürüşdürəndə işə düşür.
 * Təsadüfi toxunuşla aktivləşmir. Yarıda buraxılsa geri qayıdır.
 */
@Composable
fun SlideToConfirm(
    label: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    onConfirm: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val confirm by rememberUpdatedState(onConfirm)
    val view = LocalView.current
    val shape = RoundedCornerShape(height / 2)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(CarColors.PanelAlt)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
    ) {
        val density = LocalDensity.current
        val thumb = height - 8.dp
        val maxPx = with(density) { (maxWidth - thumb - 8.dp).toPx() }.coerceAtLeast(1f)
        val progress = (offset.value / maxPx).coerceIn(0f, 1f)

        // İz: sürüşdükcə rənglənir
        Box(
            Modifier
                .fillMaxHeight()
                .width(with(density) { (offset.value + thumb.toPx() + 8.dp.toPx()).toDp() })
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.10f), color.copy(alpha = 0.45f))))
        )
        // Yazı (sürüşdükcə solur)
        Text(
            "$label  ›››",
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = thumb, end = 12.dp)
                .alpha(1f - progress),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = CarColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Sürüşən düymə
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(RoundedCornerShape(thumb / 2))
                .background(color)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value >= maxPx * 0.85f) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    offset.animateTo(maxPx)
                                    confirm()
                                }
                                offset.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } }
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(0f, maxPx)) }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.Black, modifier = Modifier.size(thumb * 0.55f))
        }
        Icon(
            Icons.Filled.ChevronRight, null,
            tint = color.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).alpha(1f - progress)
        )
    }
}
