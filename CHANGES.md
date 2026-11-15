# OpenCode Mobile 0.1.2-debug — что исправлено

Неофициальный Android-клиент OpenCode (не продукт команды OpenCode).
Пакет: `ru.dredd20.opencode`. versionName **0.1.2**, versionCode **3**.
Подпись: тот же debug-сертификат, что у 0.1.0 и 0.1.1
(SHA-256 `65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90`).
Сам ключ в репозитории и в истории git **больше не лежит**: он вынесен в
`/workspace/opencode-android-keys/` (или другой путь) и подхватывается через
`local.properties` (`debug.keystore=`) либо `OPENCODE_DEBUG_KEYSTORE`.

Ниже — пункты отчёта тестера 0.1.1 (`/workspace/opencode-test/run2/REPORT.md`).
Время проверки: 15 ноября 2026, зона Asia/Krasnoyarsk (UTC+7).

## БАГ-23. RejectedExecutionException и «Address already in use» после Stop/Start

**Сделано.** Accept-цикл Phone API идёт в отдельном потоке `phone-accept`, не в общем пуле.
На каждый Start создаётся новый cached-пул. Перед повторным bind сокет закрывается,
файл снимается, в abstract/path-имя делается короткий connect, чтобы разбудить
заблокированный `accept()`. `RejectedExecutionException` и прочие ошибки логируются
и не роняют процесс. Stale-intent `ACTION_STOP`, дошедший уже после нового `ensure()`,
игнорируется (счётчик эпохи).
**Проверено.** На `murf-embed35` 20 циклов Stop/Start подряд: порт 33627 переиспользован
20/20, новые pid, FATAL 0, пароля в argv нет. Скриншот `04-after-20-cycles.png`.

## БАГ-18. Системная Назад после смены порта открывает старый порт

**Сделано.** `WebHistoryPolicy` сравнивает origin вместе с портом (`java.net.URI`).
После смены origin WebView очищает историю в `onPageFinished`. `goBack()` разрешён
только к записи того же origin+port. Предпочтительный порт и пароль пишутся в
`last-serve.json` (Stop удаляет только `serve-state.json`) и переиспользуются,
если порт свободен — чтобы не сбрасывать cookie/localStorage.
**Проверено.** 20/20 переиспользований порта 33627 после Stop/Start. Юнит-тесты
`WebHistoryPolicyTest`. Смена порта «насильно» (занятый старый порт) на устройстве
отдельным сценарием не гонялась.

## БАГ-20. Возврат из Настроек перезагружает веб-UI

**Сделано.** Session, Settings и About живут в одном `Box`: скрытый Session остаётся
в композиции (`Modifier.size(0.dp)`), настройки — оверлей `Surface`. Повторное
присоединение WebView больше не делает `forceReload`.
**Проверено.** Кодом и возвратом из Settings на живой сессии (первый Back закрывает
настройки, сессия на месте). Скриншот `05-back-from-settings.png`.

## БАГ-12. Retry снова качает ~115 МБ

**Сделано.** Маркеры `.opencode-cli-version`, `.opencode-apt-ok`, `.opencode-config-ok`.
Тарболлы после успеха не удаляются. Загрузка пропускается, если sha256 совпадает;
установка CLI — если бинарь и маркер версии совпадают; apt — если маркер есть и
git/rg/python3 на месте. Retry начинает с первого незавершённого шага.
**Проверено.** Юнит-тесты `SetupSkipTest`. На обновлённом эмуляторе среда уже была
готова (кэш/rootfs с 0.1.1). Прерывание сети во время apt и во время download на
устройстве в этом прогоне **не делалось** — это остаётся пробелом проверки.

## БАГ-19. Первая Назад на Настройках не закрывает их

**Сделано.** Убран перехват `onKeyDown(BACK)` в Activity. `OnBackPressedCallback`
смотрит на текущий экран: Settings/About никогда не вызывают `WebView.goBack()`.
**Проверено.** На `murf-embed35` первая Назад закрыла Settings и вернула к списку
сессий (`settings_closed_first_back=true`, `05-back-from-settings.png`).

## БАГ-11. Режим Termux

**Сделано.** Start и Stop всегда видны и действуют на текущий режим. При смене режима,
если сервер запущен, спрашивается подтверждение, затем предыдущий режим останавливается.
Вместо бесконечного «Waiting» — таймаут с ошибкой. Если Termux не установлен —
понятный текст плюс инструкция: F-Droid/GitHub, `allow-external-apps=true` в
`~/.termux/termux.properties`, `termux-reload-settings`, разрешение `RUN_COMMAND`.
**Проверено.** Кодом и UI-состоянием «Termux не установлен». Полный цикл с установкой
Termux (F-Droid/GitHub x86_64) на эмуляторе в этом прогоне **не завершён**.

## БАГ-21. Remote-статус смешивает встроенный и удалённый сервер

**Сделано.** Статус Remote берётся только из `RemoteOrigin` / remote ServeState, без
сообщения embedded `RuntimeService`. `RemoteOrigin.parse` не подставляет порт 80
для неполного URL (`http://` → null).
**Проверено.** Юнит-тесты `RemoteOriginTest`. Живой E2E к LAN-серверу в этом прогоне
не повторялся.

## БАГ-22 и JSON геолокации

**Сделано.** `termux-torch off` разбирает argv (`TorchArgs.parseOn`): `off` не
считается «включить». Ошибки CLI, в том числе `permission_pending` и «API недоступен»,
всегда печатаются как JSON в stdout, не голой строкой и не traceback. `SecurityException`
на location/torch уходит в `permission_pending`. Скрипты Phone API переписываются из
assets при каждом Start, чтобы обновление с 0.1.1 подхватило правки без полной
переустановки rootfs.
**Проверено.** Юнит-тесты `TorchAndClientJsonTest`. Живой вызов `termux-torch off` /
`termux-location` в госте проверяется в тестовом журнале 0.1.2 (нужен поднятый
Phone API после Start).

## Ключ в git

**Сделано.** `keystore/`, `*.keystore`, `*.jks` в `.gitignore`. Локальная история
ветки `feature/v0.1` переписана (`git filter-branch` от `3ffe98b..HEAD`), исходный
коммит 0.1.1 с ключом заменён на `8dd0b88`. Репозиторий никуда не пушился.
`git fsck --full` чист, в объектах нет `keystore` / `local.properties`.

## Модель и уведомление

**Проверено** 15 ноября 2026 после 14:00 (UTC+7), лимит бесплатной модели к этому
моменту уже сбрасывался. Через официальный API (провайдер `opencode`, модель
`big-pickle`) сессия ответила текстом `PONG` (finish=stop, cost 0). Пока приложение
было в фоне, пришло уведомление канала `opencode_agent`: «Agent reply is ready» /
«OpenCode finished a reply…». Скриншоты `10-notification-shade.png`,
`12-pong-reply.png`. (Первая попытка ввести промпт в поиск сессий — ложное
срабатывание, в зачёт не идёт.)

## Сборка и тесты

Юнит-тесты: 51, ошибок 0. Debug-APK подписан тем же сертификатом, versionName 0.1.2,
versionCode 3. Ничего не пушилось.


## Устройства в этом прогоне

- `murf-embed35` (emulator-5580): обновление 0.1.1 → 0.1.2 (firstInstall 02:15, lastUpdate 14:38 UTC+7).
  20 циклов Stop/Start на первой сборке 0.1.2 (порт 33627, FATAL 0) и ещё 10+3 после правки гонки Phone API
  (порт снова 33627, FATAL/ANR/RejectedExecution/Address already in use — 0; сокет Phone API после паузы 2 с — на месте).
  `termux-torch off` → JSON `{"on": false}`; `on` → `{"on": true}`. `termux-location` при выключенной категории —
  JSON с ошибкой категории; после включения категории без системного разрешения —
  `{"error":"permission_pending","permission":"android.permission.ACCESS_FINE_LOCATION"}`.
  MCP: `✓ phone  connected`. Режим Termux без приложения: статус «Install Termux (F-Droid/GitHub)»,
  Start/Stop на месте, диалог «Stop the current server and switch mode?».
- `oc-tablet35` (emulator-5582): **чистая** установка 0.1.2. Кэш ubuntu/opencode заранее положен в `dl/`;
  mtime архивов не изменился (проверка sha, не повторная загрузка). `install.log`: «28 of 28 MB» / «115 of 115 MB»
  в ту же секунду, затем распаковка, proot ok, apt, «Ready: OpenCode 2.3.0» (~65 с). 5 циклов Stop/Start,
  порт 42367, Phone API жив, FATAL 0. Первая Назад закрывает Settings и возвращает к веб-UI.
