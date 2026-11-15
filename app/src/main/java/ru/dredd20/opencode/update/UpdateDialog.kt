package ru.dredd20.opencode.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.dredd20.opencode.BuildConfig
import ru.dredd20.opencode.i18n.tr

private fun versionLine(remote: UpdateManifest.Info?): String = buildString {
    append(tr("Установлено: ", "Installed: ")).append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
    if (remote != null) append("\n").append(tr("В релизе: ", "Release: ")).append(remote.versionName).append(" (").append(remote.versionCode).append(")")
}

/** App update dialog, shown over any screen. Re-reads the install permission on every resume. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { AppUpdater.onResume(context) }
    val show by AppUpdater.dialog.collectAsState()
    val phase by AppUpdater.phase.collectAsState()
    val canInstall by AppUpdater.canInstall.collectAsState()
    if (!show || phase is AppUpdater.Phase.Idle) return
    val info = AppUpdater.infoOf(phase)
    val busy = phase is AppUpdater.Phase.Downloading || phase is AppUpdater.Phase.Installing || phase is AppUpdater.Phase.Checking
    AlertDialog(
        onDismissRequest = { if (!busy) AppUpdater.dismiss(context) },
        title = {
            Text(
                if (info != null) tr("Обновление OpenCode Mobile ${info.versionName}", "OpenCode Mobile update ${info.versionName}")
                else tr("Обновления", "Updates"),
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (val p = phase) {
                    is AppUpdater.Phase.Checking -> {
                        Text(tr("Проверяю GitHub Releases…", "Checking GitHub Releases…"))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    is AppUpdater.Phase.UpToDate -> Text(tr("У вас последняя версия.", "You have the latest version.") + "\n" + versionLine(p.remote))
                    is AppUpdater.Phase.Available -> Text(versionLine(p.info) + (if (p.info.size > 0) "\n" + tr("Размер: ", "Size: ") + "%.1f MB".format(p.info.size / 1048576.0) else ""))
                    is AppUpdater.Phase.Downloading -> {
                        Text(tr("Скачивание… ", "Downloading… ") + "${(p.progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    is AppUpdater.Phase.Ready -> Text(
                        if (canInstall) tr(
                            "Файл проверен: sha256 и подпись совпадают. Нажмите «Установить». Локальный сервер будет остановлен.",
                            "File verified: sha256 and signature match. Tap Install. The local server will be stopped.",
                        ) else tr(
                            "Файл проверен. Разрешите установку: откроются настройки, включите «Разрешить установку из этого источника» и вернитесь назад.",
                            "File verified. Allow installs: settings will open, turn on “Allow from this source” and come back.",
                        ),
                    )
                    is AppUpdater.Phase.Installing -> Text(tr("Подтвердите установку в окне Android.", "Confirm the installation in the Android dialog."))
                    is AppUpdater.Phase.Failed -> Text(p.message, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
                if (info != null && info.notes.isNotBlank() && (phase is AppUpdater.Phase.Available || phase is AppUpdater.Phase.Ready)) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (AppUpdater.usesTestServer) {
                    Text("TEST SERVER: ${BuildConfig.UPDATE_MANIFEST_URL}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                is AppUpdater.Phase.Available -> TextButton(onClick = { AppUpdater.download(context, p.info) }) { Text(tr("Скачать", "Download")) }
                is AppUpdater.Phase.Ready -> TextButton(onClick = { AppUpdater.onReadyAction(context) }) {
                    Text(
                        when (UpdateManifest.readyAction(canInstall)) {
                            UpdateManifest.ReadyAction.INSTALL -> tr("Установить", "Install")
                            UpdateManifest.ReadyAction.OPEN_SETTINGS -> tr("Открыть настройки", "Open settings")
                        },
                    )
                }
                is AppUpdater.Phase.Failed -> if (p.info != null) {
                    TextButton(onClick = { AppUpdater.download(context, p.info) }) { Text(tr("Повторить", "Retry")) }
                } else {
                    TextButton(onClick = { AppUpdater.dismiss(context) }) { Text("OK") }
                }
                else -> TextButton(onClick = { AppUpdater.dismiss(context) }, enabled = !busy) { Text("OK") }
            }
        },
        dismissButton = {
            if (!busy && (phase is AppUpdater.Phase.Available || phase is AppUpdater.Phase.Ready || (phase is AppUpdater.Phase.Failed && info != null))) {
                TextButton(onClick = { AppUpdater.dismiss(context) }) { Text(tr("Позже", "Later")) }
            }
        },
    )
}
