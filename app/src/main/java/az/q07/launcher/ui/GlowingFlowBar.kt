package az.q07.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Menyu elementi. İkon ya vektor (icon), ya da tətbiq ikonu (bitmap) ola bilər.
 */
data class CarMenuItem(
    val title: String,
    val icon: ImageVector? = null,
    val bitmap: ImageBitmap? = null,
    val subtitle: String? = null
)

val DefaultGlow = Color(0xFF00E5FF)

/** Neon kapsul: yumşaq qradient fon, neon çərçivə, kənar işıq kölgəsi, altda ağ fokus zolağı */
private fun Modifier.glowCapsule(glowColor: Color, cornerDp: Dp): Modifier {
    val shape = RoundedCornerShape(cornerDp)
    return this
        .shadow(elevation = 18.dp, shape = shape, ambientColor = glowColor, spotColor = glowColor)
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(glowColor.copy(alpha = 0.32f), glowColor.copy(alpha = 0.14f))
            )
        )
        .drawBehind {
            val corner = CornerRadius(cornerDp.toPx(), cornerDp.toPx())
            drawRoundRect(
                color = glowColor.copy(alpha = 0.9f),
                size = size,
                cornerRadius = corner,
                style = Stroke(width = 1.8.dp.toPx())
            )
            val barW = size.width * 0.45f
            val barLeft = (size.width - barW) / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(barLeft, size.height - 3.dp.toPx()),
                size = Size(barW, 3.dp.toPx()),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
}

/** Elementin məzmunu: aktivlikdən asılı rəng, basılanda yüngül "batma" */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlowItem(
    item: CarMenuItem,
    index: Int,
    animatedIndex: Float,
    glowColor: Color,
    itemShape: RoundedCornerShape,
    modifier: Modifier,
    onItemSelected: (Int) -> Unit,
    onItemLongClick: ((Int) -> Unit)?,
    alignStart: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val proximity = (1f - abs(animatedIndex - index)).coerceIn(0f, 1f)
    val isActive = proximity > 0.55f || isPressed

    val contentColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.55f),
        animationSpec = tween(150), label = "textColor"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isActive) glowColor else Color.White.copy(alpha = 0.55f),
        animationSpec = tween(150), label = "iconTint"
    )
    val pressOffset by animateDpAsState(
        targetValue = if (isPressed) 1.dp else 0.dp,
        animationSpec = tween(100), label = "pressFeedback"
    )

    Box(
        modifier = modifier
            .offset(y = pressOffset)
            .clip(itemShape)
            // Standart boz ripple söndürülüb — yalnız neon işıq görünür
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClick = onItemLongClick?.let { f -> { f(index) } },
                onClick = { onItemSelected(index) }
            )
            .padding(horizontal = 12.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                item.bitmap != null -> Image(
                    bitmap = item.bitmap,
                    contentDescription = item.title,
                    alpha = if (isActive) 1f else 0.75f,
                    modifier = Modifier.size(38.dp)
                )
                item.icon != null -> Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column {
                Text(
                    text = item.title,
                    color = contentColor,
                    fontSize = 17.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle != null) {
                    Text(item.subtitle, color = contentColor.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Üfüqi neon menyu: işıq kapsulu bir düymədən digərinə axaraq keçir (sliding glow + motion stretch).
 */
@Composable
fun GlowingFlowNavigationBar(
    items: List<CarMenuItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: ((Int) -> Unit)? = null,
    glowColor: Color = DefaultGlow,
    barHeight: Dp = 68.dp
) {
    if (items.isEmpty()) return

    val containerShape = RoundedCornerShape(20.dp)
    val itemShape = RoundedCornerShape(15.dp)

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "lightFlowPosition"
    )
    val travelDistance = abs(selectedIndex - animatedIndex)
    val stretchFactor = (travelDistance * 0.35f).coerceIn(0f, 0.45f)

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

        val itemWidthPx = with(density) { itemWidth.toPx() }
        val stepPx = itemWidthPx + with(density) { spacing.toPx() }
        val currentLeftPx = animatedIndex * stepPx
        val extraStretchPx = itemWidthPx * stretchFactor
        val isMovingRight = selectedIndex > animatedIndex

        val indicatorLeftPx =
            if (isMovingRight) (currentLeftPx - extraStretchPx * 0.4f).coerceAtLeast(0f) else currentLeftPx
        val indicatorWidthPx = itemWidthPx + extraStretchPx

        if (selectedIndex in items.indices) {
            Box(
                modifier = Modifier
                    .offset(x = with(density) { indicatorLeftPx.toDp() })
                    .width(with(density) { indicatorWidthPx.toDp() })
                    .fillMaxHeight()
                    .glowCapsule(glowColor, 15.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                GlowItem(
                    item, index, animatedIndex, glowColor, itemShape,
                    Modifier.weight(1f).fillMaxHeight(),
                    onItemSelected, onItemLongClick, alignStart = false
                )
            }
        }
    }
}

/**
 * Şaquli neon menyu (landscape dock və Ayarlar menyusu üçün) — eyni axıcı işıq effekti, Y oxu üzrə.
 */
@Composable
fun GlowingFlowNavigationColumn(
    items: List<CarMenuItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: ((Int) -> Unit)? = null,
    glowColor: Color = DefaultGlow,
    itemHeight: Dp = 68.dp,
    spacing: Dp = 8.dp
) {
    if (items.isEmpty()) return

    val containerShape = RoundedCornerShape(20.dp)
    val itemShape = RoundedCornerShape(15.dp)

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "lightFlowPositionY"
    )
    val travelDistance = abs(selectedIndex - animatedIndex)
    val stretchFactor = (travelDistance * 0.35f).coerceIn(0f, 0.45f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight * items.size + spacing * (items.size - 1) + 12.dp)
            .clip(containerShape)
            .background(Color(0xFF0D1117).copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), containerShape)
            .padding(6.dp)
    ) {
        val density = LocalDensity.current
        val itemHeightPx = with(density) { itemHeight.toPx() }
        val stepPx = itemHeightPx + with(density) { spacing.toPx() }
        val currentTopPx = animatedIndex * stepPx
        val extraStretchPx = itemHeightPx * stretchFactor
        val isMovingDown = selectedIndex > animatedIndex

        val indicatorTopPx =
            if (isMovingDown) (currentTopPx - extraStretchPx * 0.4f).coerceAtLeast(0f) else currentTopPx
        val indicatorHeightPx = itemHeightPx + extraStretchPx

        if (selectedIndex in items.indices) {
            Box(
                modifier = Modifier
                    .offset(y = with(density) { indicatorTopPx.toDp() })
                    .height(with(density) { indicatorHeightPx.toDp() })
                    .fillMaxWidth()
                    .glowCapsule(glowColor, 15.dp)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            items.forEachIndexed { index, item ->
                GlowItem(
                    item, index, animatedIndex, glowColor, itemShape,
                    Modifier.fillMaxWidth().height(itemHeight),
                    onItemSelected, onItemLongClick, alignStart = true
                )
            }
        }
    }
}
