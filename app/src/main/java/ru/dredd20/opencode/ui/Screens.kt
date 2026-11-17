package ru.dredd20.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.dredd20.opencode.BuildConfig
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.prefs.AppPrefs
import ru.dredd20.opencode.prefs.RuntimeMode
import ru.dredd20.opencode.runtime.OpenCodeRelease
import ru.dredd20.opencode.serve.CleartextPolicy

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
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OpenCode Mobile", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth())
        Text(tr("Неофициальный клиент, не продукт команды OpenCode.", "Unofficial client, not a product of the OpenCode team."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        Text(
            tr(
                "Будет скачан Ubuntu 24.04 и официальный OpenCode ${OpenCodeRelease.VERSION} (проверка sha256). Нужен интернет. Загрузка около 150 МБ, на диске после установки около 800 МБ. Затем поднимется локальный сервер.",
                "This will download Ubuntu 24.04 and official OpenCode ${OpenCodeRelease.VERSION} (sha256 verified). Internet is required. About 150 MB download and about 800 MB on disk after setup, then a local server is started.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (installing || progressPct > 0) {
            LinearProgressIndicator(progress = { (progressPct.coerceIn(0, 100)) / 100f }, modifier = Modifier.fillMaxWidth())
            Text(StatusText.setupProgress(progressPct, message, error), modifier = Modifier.fillMaxWidth())
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            Button(onClick = onRetry, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) { Text(tr("Повторить", "Retry")) }
        } else if (!installing) {
            Button(onClick = onInstall, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) { Text(tr("Установить", "Install")) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
    termuxHint: String? = null,
    updateAutoCheck: Boolean = true,
    onUpdateAutoCheck: (Boolean) -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    updateSource: String = "",
    onPlugins: () -> Unit = {},
    termuxActions: TermuxActions? = null,
    termuxBusy: Boolean = false,
    termuxError: String? = null,
    modifier: Modifier = Modifier,
) {
    var showLogs by remember { mutableStateOf(twoPane) }
    var showPassword by remember { mutableStateOf(false) }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Режим", "Mode"), style = MaterialTheme.typography.titleMedium)
            ModeRow(tr("Встроенный (по умолчанию)", "Embedded (default)"), mode == RuntimeMode.EMBEDDED) { onMode(RuntimeMode.EMBEDDED) }
            ModeRow(tr("Termux", "Termux"), mode == RuntimeMode.TERMUX) { onMode(RuntimeMode.TERMUX) }
            ModeRow(tr("Удалённый сервер", "Remote server"), mode == RuntimeMode.REMOTE) { onMode(RuntimeMode.REMOTE) }
            if (mode == RuntimeMode.TERMUX && !termuxHint.isNullOrBlank()) {
                if (termuxActions != null) TermuxSetupBlock(termuxHint, termuxActions, error = termuxError)
                else Text(termuxHint, style = MaterialTheme.typography.bodySmall)
            }
            if (mode == RuntimeMode.REMOTE) {
                OutlinedTextField(remoteUrl, onRemoteUrl, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (CleartextPolicy.shouldWarnCleartext(remoteUrl)) {
                    Text(
                        tr(
                            "Это незашифрованный HTTP. Трафик (включая пароль) может быть виден в сети. Для публичного интернета используйте HTTPS. Для домашней сети это обычный режим opencode serve.",
                            "This is unencrypted HTTP. Traffic (including the password) may be visible on the network. Use HTTPS on the public internet. On a home LAN this is normal for opencode serve.",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(remoteUser, onRemoteUser, label = { Text(tr("Логин", "Username")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    remotePassword,
                    onRemotePassword,
                    label = { Text(tr("Пароль", "Password")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) tr("Скрыть", "Hide") else tr("Показать", "Show"))
                        }
                    },
                )
            }
            Text(if (message.isBlank()) phase else "$phase — $message", style = MaterialTheme.typography.bodySmall)
            if (phase == "STOPPING" || termuxBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = (!running && !termuxBusy) || mode == RuntimeMode.REMOTE) { Text(tr("Запустить", "Start")) }
                OutlinedButton(onClick = onStop, enabled = running || mode == RuntimeMode.REMOTE) { Text(tr("Стоп", "Stop")) }
                if (mode == RuntimeMode.EMBEDDED) {
                    OutlinedButton(onClick = onUpdate) { Text(tr("Обновить OpenCode", "Update OpenCode")) }
                }
            }
            Text(tr("Плагины (MCP)", "Plugins (MCP)"), style = MaterialTheme.typography.titleMedium)
            Text(tr("Каталог удалённых MCP-серверов и установленные серверы.", "Catalog of remote MCP servers and installed servers."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPlugins) { Text(tr("Открыть «Плагины»", "Open “Plugins”")) }
            Text(tr("Доступы телефона", "Phone access"), style = MaterialTheme.typography.titleMedium)
            CategoryToggle(tr("Датчики и батарея", "Sensors and battery"), categories[AppPrefs.CAT_SENSORS] == true) { onCategory(AppPrefs.CAT_SENSORS, it) }
            CategoryToggle(tr("Геолокация и Wi-Fi (info и сканирование, выкл. по умолчанию)", "Location and Wi-Fi (info and scan, off by default)"), categories[AppPrefs.CAT_LOCATION] == true) { onCategory(AppPrefs.CAT_LOCATION, it) }
            CategoryToggle(tr("Камера (выкл. по умолчанию)", "Camera (off by default)"), categories[AppPrefs.CAT_CAMERA] == true) { onCategory(AppPrefs.CAT_CAMERA, it) }
            CategoryToggle(tr("Буфер обмена (выкл. по умолчанию)", "Clipboard (off by default)"), categories[AppPrefs.CAT_CLIPBOARD] == true) { onCategory(AppPrefs.CAT_CLIPBOARD, it) }
            CategoryToggle(tr("Уведомления и toast", "Notifications and toasts"), categories[AppPrefs.CAT_NOTIFY] == true) { onCategory(AppPrefs.CAT_NOTIFY, it) }
            CategoryToggle(tr("Система (вибрация, фонарик, TTS, громкость, share, набор номера)", "System (vibrate, torch, TTS, volume, share, dialer)"), categories[AppPrefs.CAT_SYSTEM] == true) { onCategory(AppPrefs.CAT_SYSTEM, it) }
            CategoryToggle(tr("USB", "USB"), categories[AppPrefs.CAT_USB] == true) { onCategory(AppPrefs.CAT_USB, it) }
            CategoryToggle(tr("Bluetooth", "Bluetooth"), categories[AppPrefs.CAT_BLUETOOTH] == true) { onCategory(AppPrefs.CAT_BLUETOOTH, it) }
            CategoryToggle(tr("Wi-Fi подключение", "Wi-Fi connect"), categories[AppPrefs.CAT_WIFI] == true) { onCategory(AppPrefs.CAT_WIFI, it) }
            CategoryToggle(tr("Сессии с железом (USB serial, SPP, TCP)", "Hardware sessions (USB serial, SPP, TCP)"), categories[AppPrefs.CAT_HARDWARE] == true) { onCategory(AppPrefs.CAT_HARDWARE, it) }
            CategoryToggle(tr("NFC (выкл. по умолчанию)", "NFC (off by default)"), categories[AppPrefs.CAT_NFC] == true) { onCategory(AppPrefs.CAT_NFC, it) }
            CategoryToggle(tr("Связь: SMS и контакты (выкл. по умолчанию)", "Messaging: SMS and contacts (off by default)"), categories[AppPrefs.CAT_COMMS] == true) { onCategory(AppPrefs.CAT_COMMS, it) }
            Text(tr("Обновления приложения", "App updates"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Источник: $updateSource", "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Source: $updateSource"),
                style = MaterialTheme.typography.bodySmall,
            )
            CategoryToggle(tr("Проверять обновления при запуске (не чаще раза в 6 ч)", "Check for updates on launch (at most every 6 h)"), updateAutoCheck, onUpdateAutoCheck)
            OutlinedButton(onClick = onCheckUpdates) { Text(tr("Проверить обновления", "Check for updates")) }
            TextButton(onClick = onAbout) { Text(tr("О приложении и лицензии", "About and licenses")) }
            if (!twoPane) {
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(if (showLogs) tr("Скрыть журнал", "Hide log") else tr("Показать журнал", "Show log"))
                }
            }
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
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
            Column(Modifier.weight(1f).fillMaxSize()) { logPane() }
        }
    } else {
        Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            controls()
            if (showLogs) {
                Text(tr("Журнал", "Logs"), style = MaterialTheme.typography.titleMedium)
                Text(logs, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun CategoryToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, label included (run4 item 4).
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun AboutScreen(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("OpenCode Mobile ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
        Text(tr("Неофициальный клиент, не продукт команды OpenCode.", "Unofficial client, not a product of the OpenCode team."))
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
