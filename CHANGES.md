# OpenCode Mobile 0.2.1-debug: Termux и мелочи автообновления

Неофициальный Android-клиент OpenCode (не продукт команды OpenCode).
Пакет: `ru.dredd20.opencode`. versionName **0.2.1**, versionCode **11**.
Подпись: тот же debug-сертификат, что у 0.1.0–0.2.0
(SHA-256 `65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90`). Ключа в репозитории нет.
Ветка `feature/plugins` от `d09ab47` (0.2.0). Основание: отчёты тестера run3, run4 и run5.

## Termux (run3)

- **БАГ-24, установка OpenCode в Termux.** `scripts/termux-install.sh` (копия в `app/src/main/assets/termux/`,
  тест проверяет, что они совпадают). Схема из PR anomalyco/opencode#62695: glibc самого Termux
  (`glibc-repo` + `glibc-runner`), официальный `opencode-linux-<arch>.tar.gz` с проверкой sha256 (для 2.3.0 суммы
  зашиты, для других версий берётся `SHA256SUMS`), `patchelf --set-interpreter` без RPATH, лаунчер
  `$PREFIX/bin/opencode` (`unset LD_PRELOAD`, прямой `exec`), `allow-external-apps=true`. Проверяет, что
  `patchelf` и `curl` реально запускаются; на свежем Termux доставляет актуальный `libc++` (без этого `patchelf`
  падал с `CANNOT LINK EXECUTABLE … __hash_memory`), при необходимости делает `full-upgrade`.
  На x86_64 Android seccomp убивает glibc-загрузчик (`access`, SIGSYS, код 159): скрипт и приложение пишут это
  прямо. Поддерживаемая цель: arm64.
- В приложении (режим Termux): шаги, кнопки «Скопировать команду установки» (heredoc в `~/ocm-termux-install.sh`
  + `bash`), «Открыть Termux», «Разрешить запуск команд в Termux», текст ошибки. Состояние разрешения обновляется
  после выдачи и при возврате в приложение.
- **БАГ-25.** `backend/Termux.kt`: `RUN_COMMAND` с `PendingIntent`, приходят stdout, stderr, код выхода и `errmsg`
  Termux. `TermuxFailure.describe` переводит коды (127 нет opencode, 126 бинарник не запускается, 64 старый
  Termux), чистит markdown из `errmsg`, скрывает пароль, оставляет последние 15 строк. Показывается под кнопкой
  «Запустить» и пишется в `files/opencode/host/logs/termux.log` (там же хвост `serve.log`).
- **Пароль Termux не в argv.** `backend/TermuxScripts.kt`: пароль идёт в stdin, скрипт пишет `serve.env` (0600,
  каталог `~/.opencode-mobile` 0700) и делает `exec opencode serve`. Перед стартом `opencode --version` (60 с).
- **Стоп без `pkill -f`.** Убивается только pid из `serve.pid`, если его `/proc/<pid>/cmdline` содержит
  `serve … --port <наш порт>`; TERM, ожидание, KILL. Чужие процессы и переиспользованный pid не трогаются.
- `TermuxServe`: health-проверка раньше шла на главном потоке (NetworkOnMainThreadException глотался, всегда
  таймаут 20 с). Теперь IO + Mutex, ожидание до 90 с, при таймауте запускается stop-скрипт.
- `termux-location`: сначала свежая точка (`getCurrentLocation` на API 30+, иначе `requestSingleUpdate`) по всем
  включённым провайдерам, таймаут 15 с (`-T`), потом самая свежая last known. В ответе `source` и `age_ms`.
- Ошибка Setup показывается один раз, порт в статусе один раз (`ui/StatusText.kt`), пустой статус без «IDLE — ».
- `RuntimeService`: Start, Stop и установка сериализованы (`Mutex`), `stopSelfResult(lastStartId)`,
  `stopRequested` сбрасывается под замком. Первый Start после Stop больше не гасится остановкой сервиса.

## Автообновление и настройки (run4)

1. Повторная «Проверить обновления» перепроверяет файл (есть, размер, sha256) перед «Файл проверен». Если файла нет
   или он испорчен, состояние сбрасывается и снова предлагается «Скачать».
2. Обрыв сети: «Загрузка не удалась: … «Повторить» продолжит с места обрыва.» вместо «Обновление отклонено».
3. Докачка: `<name>.part` + `Range`, проверка `Content-Range`, sha256 в конце. Ошибки проверки удаляют `.part`,
   сетевые ошибки его оставляют (`update/UpdateDownload.kt`).
4. Пароль Remote шифруется AES-256-GCM ключом `ocm_remote_password_v1` из AndroidKeyStore
   (`prefs/SecretPrefs.kt`, формат `v1:iv:ct`). Старое значение `remote_password` переносится при старте
   приложения и удаляется. Если Keystore недоступен, остаётся старое поведение.
5. Выключатель автопроверки (и переключатели доступов телефона) переключаются тапом по всей строке.

## Плагины (run5)

1. Шаблон заголовка из реестра (`value: "Bearer {smithery_api_key}"`) заполняется вводом пользователя
   (`HeaderTemplate.fill`). Если пользователь вставил значение целиком («Bearer …»), префикс не удваивается.
   Под полем видно, как будет записано значение. `value` сохраняется и в кэше каталога.
2. Имя по умолчанию: `title`, иначе последний сегмент id, а если он общий (`mcp`, `server`, `api`…), берётся
   предпоследний (`com.notion/mcp` → `notion`, `io.github.acme/server` → `acme`). Уникальность среди установленных
   (`notion-2`).
3. Remote/Termux: пометка «Нет в opencode.json» показывается только во встроенном режиме, где приложение читает
   настоящий конфиг. Диалог удаления в Remote/Termux говорит, что сервер отключается в работающем `opencode serve`,
   а конфиг сервера не меняется. Плашка режима переписана.
4. «Сменить ключ» у установленного сервера с заголовками (встроенный режим): новые значения пишутся в `opencode.json`
   (префикс `Bearer ` сохраняется), затем `PUT /api/experimental/mcp/:name` переподключает сервер. «Войти» (OAuth)
   показывается только у серверов без ключа. У сервера с ключом и статусом `needs_auth` написано, что ключ не
   принят, и главная кнопка там «Сменить ключ».
5. Тексты: фильтр каталога «Скрыть то, что телефон пока не запустит (оставить только удалённые HTTP-серверы)», строка
   переключается тапом целиком. Сообщение про старый WebView без «хотя бы 80»: для 80–125 без document-start
   объясняется, почему заплатку не поставить, и что нужен 126+.

## PROMPT.md

Короткий промт для продолжения разработки: устройство, модули, сборка и тесты, правила, известные баги, план
(OAuth через Custom Tabs, Node и uv в rootfs, браузер, USB fd, стабильный ключ).

## Тесты

117 unit-тестов, 0 ошибок (было 89). Новые: `TermuxScriptsTest` (serve/stop в настоящем bash с фальшивым
opencode, чужой процесс выживает, переиспользованный pid, коды выхода, скрытие пароля, очистка ошибки Termux,
копии скрипта совпадают, зашитые sha256, `bash -n`, команда вставки), `StatusTextTest`, `LocationPlanTest`,
`UpdateDownloadTest`, `SecretPrefsBlobTest`, в `RegistryTest` шаблоны заголовков, смена ключа, имена по умолчанию,
в `WebViewSupportTest` текст для WebView 91.
