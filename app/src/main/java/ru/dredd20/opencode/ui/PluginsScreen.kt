package ru.dredd20.opencode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.plugins.Availability
import ru.dredd20.opencode.plugins.Installed
import ru.dredd20.opencode.plugins.InstalledServer
import ru.dredd20.opencode.plugins.PluginsController
import ru.dredd20.opencode.plugins.Registry
import ru.dredd20.opencode.plugins.RegistryServer
import java.text.DateFormat
import java.util.Date

/** Native «Plugins» screen: installed MCP servers and the Official MCP Registry catalog. Two panes on tablets. */
@Composable
fun PluginsScreen(
    ctl: PluginsController,
    twoPane: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<RegistryServer?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var editKeys by remember { mutableStateOf<InstalledServer?>(null) }
    LaunchedEffect(Unit) { ctl.start() }
    BackHandler(enabled = selected != null && !twoPane) { selected = null }

    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("Установленные", "Installed") + " (${ctl.installed.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("Каталог", "Catalog")) })
            }
            ctl.message?.let { msg ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ctl.message = null }) { Text("OK") }
                }
            }
            if (tab == 0) InstalledList(ctl, onSignIn, onRemove = { confirmRemove = it }, onEditKeys = { editKeys = it }, onCatalog = { tab = 1 })
            else CatalogList(ctl, selectedName = selected?.name, onSelect = { selected = it })
        }
    }

    if (twoPane) {
        Row(modifier.fillMaxSize()) {
            list(Modifier.weight(0.45f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight()) { HorizontalDivider(Modifier.fillMaxHeight().width(1.dp)) }
            Box(Modifier.weight(0.55f).fillMaxHeight()) {
                val s = selected
                if (s != null && tab == 1) ServerDetail(ctl, s, onClose = { selected = null }, onInstalled = { selected = null; tab = 0 })
                else Text(
                    tr("Выберите сервер в каталоге, чтобы посмотреть подробности и установить.", "Pick a server in the catalog to see details and install it."),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
        }
    } else {
        val s = selected
        if (s != null) ServerDetail(ctl, s, onClose = { selected = null }, onInstalled = { selected = null; tab = 0 }, modifier = modifier)
        else list(modifier.fillMaxSize())
    }

    confirmRemove?.let { name ->
        val inCfg = ctl.installed.firstOrNull { it.name == name }?.inConfig == true
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(tr("Удалить «$name»?", "Remove “$name”?")) },
            text = { Text(removeText(ctl.persistent, inCfg)) },
            confirmButton = { TextButton(onClick = { ctl.uninstall(name); confirmRemove = null }) { Text(tr("Удалить", "Remove")) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    editKeys?.let { srv -> EditKeysDialog(ctl, srv, onClose = { editKeys = null }) }
}

/** run5 bug 3: the remove dialog must not promise an opencode.json change the app cannot make. */
private fun removeText(persistent: Boolean, inConfig: Boolean): String = when {
    !persistent -> tr(
        "Сервер отключится в работающем opencode serve. Конфиг сервера приложение не меняет: если он прописан в opencode.json сервера, после перезапуска вернётся.",
        "The server is disconnected in the running opencode serve. The app does not change the server's config: if it is listed in the server's opencode.json, it comes back after a server restart.",
    )
    inConfig -> tr("Сервер отключится и пропадёт из opencode.json вместе с сохранёнными заголовками.", "The server disconnects and is removed from opencode.json with its saved headers.")
    else -> tr("Сервер отключится. В opencode.json его нет, поэтому файл не меняется.", "The server disconnects. It is not in opencode.json, so the file is not changed.")
}

@Composable
private fun EditKeysDialog(ctl: PluginsController, s: InstalledServer, onClose: () -> Unit) {
    val values = remember(s.name) { mutableStateMapOf<String, String>() }
    val reveal = remember(s.name) { mutableStateMapOf<String, Boolean>() }
    var error by remember(s.name) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Сменить ключ «${s.name}»", "Change key for “${s.name}”")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(
                    "Введите новое значение. Пустое поле оставляет старое. Если старое значение начиналось с «Bearer », префикс сохранится.",
                    "Enter the new value. An empty field keeps the old one. If the old value started with “Bearer ”, the prefix is kept.",
                ), style = MaterialTheme.typography.bodySmall)
                s.headerNames.forEach { h ->
                    val shown = reveal[h] == true
                    OutlinedTextField(
                        values[h] ?: "", { values[h] = it },
                        label = { Text(h) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { TextButton(onClick = { reveal[h] = !shown }) { Text(if (shown) tr("Скрыть", "Hide") else tr("Показать", "Show")) } },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = ctl.busy == null, onClick = {
                error = null
                ctl.updateHeaders(s.name, values.toMap()) { err -> if (err == null) onClose() else error = err }
            }) { Text(tr("Сохранить", "Save")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Отмена", "Cancel")) } },
    )
}

@Composable
private fun InstalledList(ctl: PluginsController, onSignIn: () -> Unit, onRemove: (String) -> Unit, onEditKeys: (InstalledServer) -> Unit, onCatalog: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!ctl.persistent) item {
            Notice(tr(
                "В этом режиме приложение не видит и не меняет opencode.json сервера. Серверы из его конфига работают как обычно; установленное отсюда подключается только до перезапуска сервера. Постоянная установка есть во встроенном режиме.",
                "In this mode the app neither sees nor changes the server's opencode.json. Servers from its config work as usual; what you install here stays connected only until the server restarts. Permanent installs are in embedded mode.",
            ))
        }
        if (!ctl.serverReachable) item {
            Notice(tr("Сервер OpenCode не отвечает. Показан конфиг; статусы появятся после запуска.", "The OpenCode server is not answering. Showing the config; statuses appear once it runs."))
        } else if (ctl.warming) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("Сервер загружает MCP…", "The server is loading MCP…"), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(ctl.installed, key = { it.name }) { s ->
            InstalledCard(s, persistent = ctl.persistent, busy = ctl.busy == s.name, onSignIn = onSignIn, onRemove = { onRemove(s.name) }, onEditKeys = { onEditKeys(s) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctl.refreshInstalled(poll = true) }) { Text(tr("Обновить статус", "Refresh status")) }
                Button(onClick = onCatalog) { Text(tr("Открыть каталог", "Open catalog")) }
            }
        }
        item {
            TextButton(onClick = onSignIn) { Text(tr("Настройки → Extensions в веб-интерфейсе", "Settings → Extensions in the web UI")) }
        }
    }
}

@Composable
private fun InstalledCard(s: InstalledServer, persistent: Boolean, busy: Boolean, onSignIn: () -> Unit, onRemove: () -> Unit, onEditKeys: () -> Unit) {
    val hasKey = s.headerNames.isNotEmpty()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.builtIn) tr("Телефон (phone)", "Phone (phone)") else (s.title?.let { "$it (${s.name})" } ?: s.name),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StatusChip(s.status)
            }
            if (s.builtIn) Text(tr("Встроенный: датчики, камера, USB и др. Не удаляется; доступы — в настройках приложения.", "Built-in: sensors, camera, USB, etc. Cannot be removed; access is set in app settings."), style = MaterialTheme.typography.bodySmall)
            if (s.target.isNotBlank()) Text("${s.type} · ${s.target}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.headerNames.isNotEmpty()) Text(tr("Заголовки: ", "Headers: ") + s.headerNames.joinToString() + " (••••)", style = MaterialTheme.typography.bodySmall)
            // Only in embedded mode do we read the real opencode.json; in Termux/Remote the server's config is invisible.
            if (persistent && !s.inConfig && !s.builtIn) Text(tr("Нет в opencode.json: пропадёт после перезапуска сервера.", "Not in opencode.json: gone after a server restart."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (s.status == "needs_auth" && hasKey) Text(tr(
                "Сервер не принял ключ. Проверьте его и нажмите «Сменить ключ».",
                "The server did not accept the key. Check it and tap “Change key”.",
            ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // run5 bug 4: OAuth sign-in only for servers without an API key; keyed servers get “Change key”.
                if (s.status == "needs_auth" && !hasKey) Button(onClick = onSignIn) { Text(tr("Войти", "Sign in")) }
                if (persistent && hasKey && !s.builtIn) {
                    if (s.status == "needs_auth") Button(onClick = onEditKeys, enabled = !busy) { Text(tr("Сменить ключ", "Change key")) }
                    else OutlinedButton(onClick = onEditKeys, enabled = !busy) { Text(tr("Сменить ключ", "Change key")) }
                }
                if (!s.builtIn) OutlinedButton(onClick = onRemove, enabled = !busy) { Text(tr("Удалить", "Remove")) }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "connected" -> tr("подключён", "connected") to Color(0xFF7CDA8A)
        "needs_auth" -> tr("нужен вход", "sign-in needed") to Color(0xFFFFC857)
        "failed" -> tr("ошибка", "failed") to Color(0xFFFF7A7A)
        "disabled" -> tr("выключен", "disabled") to Color(0xFF9AA4B2)
        "pending" -> tr("подключается", "connecting") to Color(0xFF8AB4F8)
        Installed.WAITING -> tr("ожидание", "waiting") to Color(0xFF8AB4F8)
        else -> status to Color(0xFF9AA4B2)
    }
    Text("● $label", color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun CatalogList(ctl: PluginsController, selectedName: String?, onSelect: (RegistryServer) -> Unit) {
    val shown = if (ctl.onlyAvailable) ctl.catalog.filter { Registry.isRemoteInstallable(it) } else ctl.catalog
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                ctl.query, { ctl.updateQuery(it) },
                label = { Text(tr("Поиск в Official MCP Registry", "Search the Official MCP Registry")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().toggleable(value = ctl.onlyAvailable, role = Role.Switch, onValueChange = { ctl.onlyAvailable = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Скрыть то, что телефон пока не запустит (оставить только удалённые HTTP-серверы)", "Hide what the phone cannot run yet (show only remote HTTP servers)"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = ctl.onlyAvailable, onCheckedChange = null)
            }
            val stamp = if (ctl.cachedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ctl.cachedAt)) else null
            if (ctl.offline) Notice(tr("Офлайн: показан сохранённый каталог", "Offline: showing the saved catalog") + (stamp?.let { " ($it)" } ?: ""))
            ctl.catalogError?.takeIf { !ctl.offline }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(tr("Данные из Official MCP Registry. Серверы сторонние и не проверяются.", "Data from the Official MCP Registry. Servers are third-party and not vetted."), style = MaterialTheme.typography.labelSmall)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.name }) { s ->
                CatalogRow(s, installed = s.remotes.any { it.url in ctl.installedUrls }, selected = s.name == selectedName, onClick = { onSelect(s) })
            }
            item {
                when {
                    ctl.catalogLoading -> Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    ctl.nextCursor != null && !ctl.offline -> OutlinedButton(onClick = { ctl.loadMore() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Загрузить ещё", "Load more")) }
                    shown.isEmpty() -> Column {
                        Text(tr("Ничего не найдено.", "Nothing found."))
                        TextButton(onClick = { ctl.retry() }) { Text(tr("Повторить", "Retry")) }
                    }
                    ctl.offline -> TextButton(onClick = { ctl.retry() }) { Text(tr("Повторить загрузку", "Retry loading")) }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(s: RegistryServer, installed: Boolean, selected: Boolean, onClick: () -> Unit) {
    val avail = Registry.availability(s)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    installed -> Text(tr("установлен", "installed"), color = Color(0xFF7CDA8A), style = MaterialTheme.typography.labelSmall)
                    avail is Availability.Unavailable -> Text(tr("недоступно пока", "not yet"), color = Color(0xFF9AA4B2), style = MaterialTheme.typography.labelSmall)
                    else -> Text("HTTP", color = Color(0xFF8AB4F8), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(s.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ServerDetail(ctl: PluginsController, s: RegistryServer, onClose: () -> Unit, onInstalled: () -> Unit, modifier: Modifier = Modifier) {
    val avail = Registry.availability(s)
    val taken = ctl.installed.map { it.name }.toSet()
    var name by remember(s.name) { mutableStateOf(Registry.suggestName(s, taken)) }
    val values = remember(s.name) { mutableStateMapOf<String, String>() }
    val reveal = remember(s.name) { mutableStateMapOf<String, Boolean>() }
    var error by remember(s.name) { mutableStateOf<String?>(null) }
    val already = s.remotes.any { it.url in ctl.installedUrls }
    val busy = ctl.busy != null
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.displayName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(tr("Закрыть", "Close")) }
        }
        Text("${s.name} · v${s.version}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodyMedium)
        s.remotes.forEach { r -> Text("${r.type} · ${r.url}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        s.packages.forEach { p -> Text("${p.registryType}${p.runtimeHint?.let { " ($it)" } ?: ""} · ${p.identifier}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        (s.websiteUrl ?: s.repositoryUrl)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        when (avail) {
            is Availability.Unavailable -> Notice(avail.reason)
            is Availability.Installable -> {
                if (already) Text(tr("Уже установлен. Повторная установка заменит настройки.", "Already installed. Installing again replaces its settings."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text(tr("Имя в opencode.json", "Name in opencode.json")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                avail.remote.headers.forEach { h ->
                    val shown = reveal[h.name] == true
                    OutlinedTextField(
                        values[h.name] ?: "", { values[h.name] = it },
                        label = { Text(h.name + if (h.required) " *" else "") },
                        supportingText = {
                            val tpl = h.value?.takeIf { h.variables.size == 1 }
                            val hint = tpl?.let { tr("Будет записано как: ", "Written as: ") + it.replace("{${h.variables[0]}}", tr("‹ваш ввод›", "‹your input›")) }
                            val text = listOfNotNull(h.description.ifBlank { null }, hint).joinToString("\n")
                            if (text.isNotEmpty()) Text(text, maxLines = 4)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (h.secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = if (h.secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                        trailingIcon = if (h.secret) { { TextButton(onClick = { reveal[h.name] = !shown }) { Text(if (shown) tr("Скрыть", "Hide") else tr("Показать", "Show")) } } } else null,
                    )
                }
                if (avail.remote.headers.any { it.secret }) Text(
                    tr("Секреты хранятся только в opencode.json в закрытой папке приложения и не пишутся в логи.", "Secrets are stored only in opencode.json inside the app's private folder and are never logged."),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    tr("Сторонний сервер: запросы агента и их данные уйдут к его владельцу. Если серверу нужен вход через браузер (OAuth), во вкладке «Установленные» появится «Войти»; ключ там же можно сменить.",
                        "Third-party server: agent requests and their data go to its owner. If it needs a browser sign-in (OAuth), the Installed tab shows “Sign in”; a key can be changed there too."),
                    style = MaterialTheme.typography.labelSmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy, onClick = {
                        error = null
                        ctl.install(s, avail.remote, name, values.toMap()) { err -> error = err; if (err == null) onInstalled() }
                    }) { Text(if (already) tr("Переустановить", "Reinstall") else tr("Установить", "Install")) }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
    }
}
