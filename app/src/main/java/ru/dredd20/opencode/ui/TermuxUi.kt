package ru.dredd20.opencode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.dredd20.opencode.i18n.tr

/** Termux mode helpers shared by the session pane and app settings (BUG-24). */
data class TermuxActions(
    val termuxInstalled: Boolean,
    val hasPermission: Boolean,
    val onCopyInstall: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onOpenTermux: () -> Unit,
)

@Composable
fun TermuxSetupBlock(hint: String, actions: TermuxActions, error: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions.onCopyInstall, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Скопировать команду установки", "Copy install command"))
        }
        if (actions.termuxInstalled) {
            OutlinedButton(onClick = actions.onOpenTermux, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Открыть Termux", "Open Termux"))
            }
            if (!actions.hasPermission) {
                OutlinedButton(onClick = actions.onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Разрешить запуск команд в Termux", "Allow running commands in Termux"))
                }
            }
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
