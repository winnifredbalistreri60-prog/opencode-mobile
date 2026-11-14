# OpenCode Mobile 0.1.1-debug — что исправлено

Неофициальный Android-клиент OpenCode (не продукт команды OpenCode).
Пакет: `ru.dredd20.opencode`. versionName **0.1.1**, versionCode **2**.
Подпись: тот же debug-ключ, что у 0.1.0 (SHA-256 сертификата `65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90`). Ключ лежит в `keystore/debug-v0.1.keystore`, чтобы сборка с этой машины вставала поверх 0.1.0.

Ниже — все пункты отчёта тестера 0.1.0 (`/workspace/opencode-test/run1/REPORT.md`). Время проверки: 15 ноября 2026, зона Asia/Krasnoyarsk (UTC+7).

## Блокеры

### БАГ-1. apt failed: code 100
**Сделано.** Убрано безусловное `PROOT_NO_SECCOMP` (`|| true`). Как в MURF: флаг только если на хосте есть файл `noseccomp`. При установке `selfTestProot()` сначала пробует обычный proot, и только после неудачи пишет `noseccomp` и повторяет.
**Проверено.** Чистая установка на `murf-embed35` и `oc-tablet35`: «Ready: OpenCode 2.3.0», apt (git, ripgrep, python3) проходит, code 100 больше нет. В `install.log` на планшете: `proot ok: Linux localhost … x86_64`.

### БАГ-2. Веб-UI просит пароль / pairing
**Сделано.** Разобран официальный вход desktop/serve: `POST /api/pair` (Basic) выдаёт код, `GET /auth/connect/{code}` ставит HttpOnly cookie `opencode_session_${port}` и редиректит на `/`. Нативный слой сам запрашивает код и загружает WebView на `/auth/connect/{code}`. Пароль и сессия в JavaScript страницы не отдаются (в том числе стороннему JS).
**Проверено.** Скриншоты `03-webui.png`, `21-webui-after-hotfix.png`, `32-upgraded-011-webui.png`, `41-tablet-webui.png`: официальный UI (Home / сессии), без экрана pairing и без поля пароля.

### БАГ-3. MCP `phone` Failed / timeout
**Сделано.** `phone-mcp.py` пишет JSON-RPC по строкам (`json.dumps(...) + "\n"`), на входе по-прежнему принимает и line-delimited, и Content-Length. Конфиг OpenCode v2: `mcp.servers.phone = { type: "local", command: ["phone-mcp"] }` (command массивом).
**Проверено.** В госте: `opencode --server http://127.0.0.1:$port mcp list` → `✓ phone  connected` (и на телефоне, и на планшете). Прямой вызов `phone-mcp`: `initialize` / `tools/list` / `tools/call battery_status` успешны (на телефоне заряд GOOD / 100% / charging). Оговорка: голый `opencode mcp list` без `--server` по-прежнему может сказать, что серверы не настроены — это поведение CLI, смотреть нужно API или `--server`.

### БАГ-4. Краш file chooser / отмена
**Сделано.** Callback файла вызывается один раз и только из `OpenCodeWebView`. MainActivity больше не вызывает тот же `ValueCallback`.
**Проверено.** Телефон: открытие скрепки и отмена, процесс жив (`10-file-chooser-open.png`, `11-file-chooser-cancelled.png`). Планшет: SAF «Recent», отмена BACK, фокус вернулся в сессию без FATAL (`54-tablet-file-chooser-open.png`, `55-tablet-file-chooser-cancelled.png`). Выбор конкретного файла до конца не доводился (на телефоне в Downloads у SAF часто «No items»).

## Серьёзные

### БАГ-5. ERR_CLEARTEXT_NOT_PERMITTED для удалённого http
**Сделано.** `network_security_config`: cleartext разрешён (XML не умеет CIDR для частных сетей). Перед подключением к не-loopback `http://` показывается предупреждение (`CleartextPolicy`).
**Проверено.** Юнит-тесты `CleartextPolicyTest`. Живой удалённый LAN-сервер в этом прогоне не поднимался.

### БАГ-6. UI не обновляется после смены состояния
**Сделано.** Тик 800 мс теперь читается в Compose, поэтому прогресс установки, Start/Stop и смена режима перерисовываются сами.
**Проверено.** После Install экран сам переходит к Start; после Start открывается веб-UI; после Stop статус очищается (~8 с), без ухода в Настройки и назад.

### БАГ-7. Устаревший serve-state
**Сделано.** Живость сервера проверяется по pid и health HTTP; мёртвое состояние удаляется.
**Проверено.** После Stop файл `serve-state.json` пропадает; повторный Start берёт новый порт/pid. Юнит-тесты на разбор состояния.

### БАГ-8. `URL.parse` на WebView 124
**Сделано.** Полифил `URL.parse` / `URL.canParse` внедряется до скриптов страницы через `WebViewCompat.addDocumentStartJavaScript`. Если WebView слишком старый — понятное сообщение; для 124–125 показывается баннер «using a URL.parse polyfill… update to 126+».
**Проверено.** На обоих AVD WebView 124: UI открывается, баннер виден (`03-webui.png`, `41-tablet-webui.png`). Юнит-тесты `WebViewSupportTest`. WebView 126+ на этих AVD нет (нет Play).

### БАГ-9. Пароль в `ps`
**Сделано.** Пароль пишется в файл `/run/opencode-password` с режимом 0600 и подхватывается гостевым `set -a; . /run/opencode-password; set +a`. В argv `opencode serve` и в `env -i OPENCODE_PASSWORD=...` его нет. `ProotCommand.guestEnv` отвергает `OPENCODE_PASSWORD` / `PHONE_TOKEN`. `phone-client.sh` тоже не кладёт токен в argv python.
**Проверено.** `ps -A -o ARGS=` — 0 строк с паролем (телефон, планшет, после upgrade). В `/proc/pid/environ` переменная всё ещё может быть у самого сервера — это ожидаемо и не видно в `ps`.

### БАГ-10. Геолокация без системного запроса разрешения
**Сделано.** Очередь runtime-разрешений: тап по уведомлению открывает системный диалог (FINE+COARSE). Интент подтверждения — `NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT`.
**Проверено.** Юнит-тесты протокола. Системный диалог на эмуляторе в полном цикле отдельно не прогонялся (категория location по умолчанию выключена, UX-8).

## Средние и мелкие

### БАГ-11. Termux без Termux: Start молчит
**Сделано.** Если Termux не установлен / `RUN_COMMAND` недоступен, на экране показывается понятная ошибка.
**Проверено.** Кодом и сообщением в UI. Полный режим Termux на эмуляторе нет (нет F-Droid Termux).

### БАГ-12. Retry заново качает 115 МБ
**Сделано.** `installOpenCode` не качает архив повторно, если бинарь и маркер установки уже соответствуют нужной версии (если не `force` / Update).
**Проверено.** На обеих чистых установках архив брался из заранее положенного кэша `dl/` (скачивание «115 of 115 MB» — проверка уже лежащего файла). Повторный Install после Ready не прогонялся отдельно.

### БАГ-13. В статусе два разных порта
**Сделано.** Статус берёт актуальный порт из живого serve-state / health, а не смешивает со старым значением.
**Проверено.** На планшете после рестарта: «RUNNING — Server listening on 127.0.0.1:40569 · port 40569» (один порт). На телефоне после hotfix — аналогично.

### БАГ-14. Системная Назад на Настройках закрывает приложение
**Сделано.** `BackHandler` на экранах Настройки и О приложении возвращает к предыдущему экрану (сессии / Start).
**Проверено.** На планшете Назад из App settings вернула в сессию. С главного экрана без истории WebView Назад по-прежнему может свернуть приложение — это ожидаемо.

### БАГ-15. Пароль удалённого сервера без маски
**Сделано.** Поле пароля в Remote скрыто (PasswordVisualTransformation). В prefs по-прежнему хранится как обычная строка (приватные SharedPreferences) — полного шифрования нет.
**Проверено.** Кодом UI. Живой ввод на удалённом сервере в этом прогоне не снимался.

### БАГ-16. В Remote кнопка Start ничего не делает
**Сделано.** Start в режиме Remote открывает Session / WebView.
**Проверено.** Кодом. Отдельный E2E к LAN-серверу не делался.

### БАГ-17. Уведомление Phone API про `/run/phone.sock`
**Сделано.** Текст уведомления отражает реальный транспорт: abstract `@ru.dredd20.opencode.phone` или файловый `/run/phone.sock` в зависимости от API.
**Проверено.** Кодом `PhoneApiService`.

## Уведомление «ответ агента готов»
**Сделано.** `AgentEventWatcher` слушает SSE `/api/event` и при `session.idle` / `session.execution.succeeded`, если приложение в фоне, показывает уведомление.
**Проверено.** Только кодом. E2E не получился: бесплатная модель `opencode/big-pickle` вернула 429 («You've used today's free messages. They reset at 7:03 AM», зона пользователя). Чат открывается, промпт доходит до UI (`06-chat.png` / `09-session-composer.png`).

## UX из отчёта

1. **Объём и трафик на Setup.** Есть текст: Ubuntu 24.04 + OpenCode 2.3.0, ~150 МБ загрузки, ~800 МБ на диске, нужен интернет. Проверено на планшете (`40-tablet-setup.png`).
2. **Логи на телефоне.** Журнал свёрнут под «Показать журнал», чтобы Start не уезжал за экран.
3. **Install на планшете.** Кнопка ограничена по ширине, не на все 2560 px (`40-tablet-setup.png`).
4. **Режимы — radio.** Embedded / Termux / Remote — взаимоисключающие пункты, не три независимых Switch (`51-tablet-settings.png`).
5. **Подпись шестерёнки.** content-desc / заголовок «App settings» / «Настройки приложения».
6. **Один и тот же debug-ключ.** 0.1.1 подписан ключом 0.1.0 (`keystore/debug-v0.1.keystore`). Upgrade `0.1.0 → 0.1.1` на `murf-embed35`: `adb install -d -r` / `-r` успешен, данные сохранились, serve и веб-UI без пароля работают (`30`–`32`).
7. **Звонок не в comms.** `telephony-call` отнесён к категории system (только `ACTION_DIAL`), не DANGEROUS. Проверено юнит-тестом `PhoneProtocolTest`.
8. **Чувствительные категории выкл. по умолчанию.** Location, camera, clipboard, comms — off. Sensors/battery, notifications, system, USB — on. Видно в `51-tablet-settings.png`.
9. **Стоп.** По-прежнему ~8 с; на время остановки показывается индикатор Stopping.
10. **Update при запущенном сервере.** Диалог предлагает остановить и затем обновить.

## Прочее исправление этого цикла

Повторный Start после Stop в первой сборке 0.1.1 ронял процесс: `PhoneApiService` вызывал `stopSelf()` до `startForeground()` (`RemoteServiceException`). Исправлено: `startForeground()` всегда до любых ранних выходов. На `murf-embed35` после hotfix: Stop → Start, порт сменился, FATAL/ANR нет (`20`–`23`). На планшете то же (`51`–`53`, новый порт 40569).

## Юнит-тесты

Добавлены: `SessionAuthTest`, `CleartextPolicyTest`, `WebViewSupportTest`, `SecretAndMcpTest` (в т.ч. line-протокол `phone-mcp`). Обновлён `PhoneProtocolTest`. `testDebugUnitTest` — см. `test.log`.

## Что не закрыто / не проверялось

- Ответ модели в чате: дневной лимит бесплатных `opencode/*` (429). Это не баг приложения.
- `opencode mcp list` без `--server` вводит в заблуждение; смотреть `opencode --server http://127.0.0.1:$port mcp list` или `/api/mcp`.
- Выбор существующего файла в SAF до конца не подтверждён (только открытие и отмена).
- Уведомление «агент готов» не проверено на живом SSE из-за 429.
- Termux-режим, реальный arm64 / 16K / USB OTG, живой LAN http — нет устройства или окружения.
- Пароль Termux `RUN_COMMAND`, если когда-нибудь пойдёт в payload команды, может оказаться в argv у Termux — отдельный риск, встроенный режим исправлен.
- WebView 126+ на этих AVD недоступен; полифил на 124 работает.
- Cleartext для удалённого http: предупреждение есть, живой LAN не гонялся.
