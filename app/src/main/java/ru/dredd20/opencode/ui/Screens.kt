package ru.dredd20.opencode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.prefs.AppPrefs
import ru.dredd20.opencode.prefs.RuntimeMode
import ru.dredd20.opencode.runtime.OpenCodeRelease
import ru.dredd20.opencode.runtime.RuntimeService

@Composable
fun SetupScreen(
    progressPct: Int,
    message: String,
    error: String?,
    installing: Boolean,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("OpenCode Mobile", style = MaterialTheme.typography.headlineMedium)
        Text(tr("Неофициальный клиент, не продукт команды OpenCode.", "Unofficial client, not a product of the OpenCode team."), style = MaterialTheme.typography.bodyMedium)
        Text(tr("Будет скачан Ubuntu 24.04 и официальный OpenCode ${OpenCodeRelease.VERSION} (проверка sha256). Затем поднимется локальный сервер.", "This will download Ubuntu 24.04 and official OpenCode ${OpenCodeRelease.VERSION} (sha256 verified), then start a local server."), style = MaterialTheme.typography.bodyMedium)
        if (installing || progressPct > 0) {
            LinearProgressIndicator(progress = { (progressPct.coerceIn(0, 100)) / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$progressPct%  $message")
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(tr("Повторить", "Retry")) }
        } else if (!installing) {
            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text(tr("Установить", "Install")) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(tr("Установка… не выключайте устройство.", "Installing… keep the device awake."))
            }
        }
        TextButton(onClick = onRemote) { Text(tr("Подключиться к удалённому серверу", "Connect to a remote server")) }
    }
}

@Composable
fun UnsupportedAbiScreen(onRemote: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("Этот процессор не поддерживается", "This CPU is not supported"), style = MaterialTheme.typography.headlineSmall)
        Text(tr("Официальный OpenCode не выпускается для 32-битного ARM (armv7). Встроенный сервер на этом устройстве запустить нельзя. Можно подключиться к удалённому opencode serve (например, с компьютера).", "Official OpenCode is not published for 32-bit ARM (armv7). The built-in server cannot run on this device. You can connect to a remote opencode serve (for example on a PC)."))
        Button(onClick = onRemote) { Text(tr("Удалённый сервер", "Remote server")) }
    }
}

@Composable
fun SettingsScreen(
    mode: RuntimeMode,
    onMode: (RuntimeMode) -> Unit,
    remoteUrl: String,
    onRemoteUrl: (String) -> Unit,
    remoteUser: String,
    onRemoteUser: (String) -> Unit,
    remotePassword: String,
    onRemotePassword: (String) -> Unit,
    running: Boolean,
    phase: String,
    message: String,
    categories: Map<String, Boolean>,
    onCategory: (String, Boolean) -> Unit,
    logs: String,
    twoPane: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controls: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Режим", "Mode"), style = MaterialTheme.typography.titleMedium)
            ModeRow(tr("Встроенный (по умолчанию)", "Embedded (default)"), mode == RuntimeMode.EMBEDDED) { onMode(RuntimeMode.EMBEDDED) }
            ModeRow(tr("Termux", "Termux"), mode == RuntimeMode.TERMUX) { onMode(RuntimeMode.TERMUX) }
            ModeRow(tr("Удалённый сервер", "Remote server"), mode == RuntimeMode.REMOTE) { onMode(RuntimeMode.REMOTE) }
            if (mode == RuntimeMode.REMOTE) {
                OutlinedTextField(remoteUrl, onRemoteUrl, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remoteUser, onRemoteUser, label = { Text(tr("Логин", "Username")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remotePassword, onRemotePassword, label = { Text(tr("Пароль", "Password")) }, modifier = Modifier.fillMaxWidth())
            }
            Text("$phase — $message", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) Button(onClick = onStart) { Text(tr("Запустить", "Start")) }
                else OutlinedButton(onClick = onStop) { Text(tr("Стоп", "Stop")) }
                OutlinedButton(onClick = onUpdate) { Text(tr("Обновить OpenCode", "Update OpenCode")) }
            }
            Text(tr("Доступы телефона", "Phone access"), style = MaterialTheme.typography.titleMedium)
            CategoryToggle(tr("Датчики и батарея", "Sensors and battery"), categories[AppPrefs.CAT_SENSORS] == true) { onCategory(AppPrefs.CAT_SENSORS, it) }
            CategoryToggle(tr("Геолокация и Wi‑Fi info", "Location and Wi‑Fi info"), categories[AppPrefs.CAT_LOCATION] == true) { onCategory(AppPrefs.CAT_LOCATION, it) }
            CategoryToggle(tr("Камера", "Camera"), categories[AppPrefs.CAT_CAMERA] == true) { onCategory(AppPrefs.CAT_CAMERA, it) }
            CategoryToggle(tr("Буфер обмена", "Clipboard"), categories[AppPrefs.CAT_CLIPBOARD] == true) { onCategory(AppPrefs.CAT_CLIPBOARD, it) }
            CategoryToggle(tr("Уведомления и toast", "Notifications and toasts"), categories[AppPrefs.CAT_NOTIFY] == true) { onCategory(AppPrefs.CAT_NOTIFY, it) }
            CategoryToggle(tr("Система (вибрация, фонарик, TTS, громкость, share)", "System (vibrate, torch, TTS, volume, share)"), categories[AppPrefs.CAT_SYSTEM] == true) { onCategory(AppPrefs.CAT_SYSTEM, it) }
            CategoryToggle(tr("USB", "USB"), categories[AppPrefs.CAT_USB] == true) { onCategory(AppPrefs.CAT_USB, it) }
            CategoryToggle(tr("Связь: SMS и контакты (выкл. по умолчанию)", "Messaging: SMS and contacts (off by default)"), categories[AppPrefs.CAT_COMMS] == true) { onCategory(AppPrefs.CAT_COMMS, it) }
            TextButton(onClick = onAbout) { Text(tr("О приложении и лицензии", "About and licenses")) }
        }
    }
    val logPane: @Composable () -> Unit = {
        Column {
            Text(tr("Журнал", "Logs"), style = MaterialTheme.typography.titleMedium)
            Text(logs, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true))
        }
    }
    if (twoPane) {
        Row(modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { controls() }
            Column(Modifier.weight(1f).fillMaxSize()) { logPane() }
        }
    } else {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.weight(1f)) { controls() }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f)) { logPane() }
        }
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = selected, onCheckedChange = { if (it) onClick() })
    }
}

@Composable
private fun CategoryToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AboutScreen(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("OpenCode Mobile 0.1.0", style = MaterialTheme.typography.headlineSmall)
        Text(tr("Неофициальный клиент, не продукт команды OpenCode.", "Unofficial client, not a product of the OpenCode team."))
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
