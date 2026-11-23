package az.q07.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppEntry
import az.q07.launcher.data.LauncherPrefs

/** Dock slotu üçün tətbiq seçimi */
@Composable
fun AppPickerDialog(
    title: String,
    apps: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    clearLabel: String = "Slotu boşalt"
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(apps, key = { it.key }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(app.icon, null, Modifier.size(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(LauncherPrefs.displayLabel(app.key, app.label), fontSize = 18.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Bağla") }
        },
        dismissButton = {
            if (onClear != null) TextButton(onClick = onClear) { Text(clearLabel) }
        }
    )
}
