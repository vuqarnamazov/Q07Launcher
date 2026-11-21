package az.q07.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import az.q07.launcher.ui.theme.CarColors

/**
 * Dialoq pəncərələri öz LocalDensity-sini sistemdən götürür → launcher miqyası (×2, 120%…) itir
 * və dialoqlar kiçik/kəsik görünür. Bu köməkçilər launcher-in miqyasını dialoqa ötürür.
 */
@Composable
fun ScaledDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val launcherDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalDensity provides launcherDensity) { content() }
    }
}

/** AlertDialog analoqu — miqyaslanır və hündürlüyü ekrandan çıxmır (mətn hissəsi sıxılır/sürüşür) */
@Composable
fun ScaledAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    ScaledDialog(onDismissRequest) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.Center
        ) {
            val maxH = maxHeight * 0.92f
            val maxW = minOf(maxWidth * 0.92f, 620.dp)
            Column(
                modifier
                    .widthIn(min = minOf(320.dp, maxW), max = maxW)
                    .heightIn(max = maxH)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CarColors.PanelAlt)
                    .border(1.dp, CarColors.Border, RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides CarColors.TextPrimary) {
                    if (icon != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
                    if (title != null) ProvideTextStyle(
                        MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    ) { title() }
                    if (text != null) Box(Modifier.weight(1f, fill = false)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = CarColors.TextSecondary)) { text() }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
