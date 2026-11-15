# PROMPT: продолжение разработки OpenCode Mobile

Ты продолжаешь разработку **OpenCode Mobile**: неофициального Android-клиента для [OpenCode](https://github.com/anomalyco/opencode). Это не продукт команды OpenCode. Пакет `ru.dredd20.opencode`, Kotlin + Jetpack Compose, minSdk 26, targetSdk 35. Текущая версия 0.2.1 (versionCode 11), ветка `feature/plugins`. Репозиторий: github.com/winnifredbalistreri60-prog/opencode-mobile, там опубликованы `main` (0.2.0, `d09ab47`) и тег v0.1.3.

## Как устроено

- **Встроенный режим (основной).** Приложение скачивает ubuntu-base 24.04 (sha256 по `SHA256SUMS`) и распаковывает его в свою папку. Туда ставится официальный `opencode` linux-arm64 или linux-x64, затем запускается `opencode serve` на 127.0.0.1 со случайным портом и паролем. Всё это работает в PRoot (форк Termux, бинарники в `jniLibs`). UI здесь не свой: это официальный веб-интерфейс OpenCode во встроенном WebView (Basic auth, полифил `URL.parse` для WebView 80–125).
- **Режимы Termux и Remote.** В режиме Termux `opencode serve` запускается в Termux через `RUN_COMMAND` с `PendingIntent`. Пароль передаётся через stdin и файл 0600. Установщик: `scripts/termux-install.sh` (glibc-repo + patchelf, работает только на arm64). Режим Remote подключается к уже работающему `opencode serve` по URL. Пароль хранится зашифрованным ключом Android Keystore.
- **Phone API.** В гостевой системе есть `phone-client.sh` с командами, совместимыми с Termux:API (`termux-location`, `termux-battery-status` и т. д.), и MCP-сервер `phone`. Связь идёт через unix-сокет, обработку делает `PhoneHandlers`. Каждая категория доступа (датчики, геолокация, камера, буфер, уведомления, система, USB) включается в настройках отдельно.
- **Плагины (MCP).** Экран «Плагины»: каталог Official MCP Registry с кэшем и офлайн-режимом. Удалённый Streamable HTTP сервер ставится в одно касание. Приложение сливает свои изменения в `opencode.json` и не перезаписывает его. После установки идёт `PUT /api/experimental/mcp/:name`. Шаблоны заголовков (`Bearer {key}`) заполняются, ключ можно сменить. Встроенный `phone` удалить нельзя.
- **Автообновление.** Манифест `opencode-mobile-version.json` берётся из GitHub Releases (`releases/latest/download`). APK докачивается через `.part` и `Range`. Перед установкой проверяются sha256, пакет, versionCode и сертификат, установка идёт через PackageInstaller. Тестовая сборка `-PocmUpdateTestServer=http://10.0.2.2:8765` берёт обновления с локального сервера.

## Модули (`app/src/main/java/ru/dredd20/opencode/`)

| Пакет | Ключевые файлы |
|---|---|
| корень | `MainActivity.kt` (экраны, режимы, Termux-кнопки), `OpenCodeApp.kt` |
| `runtime/` | `RuntimeService.kt` (foreground-сервис; start/stop/install под Mutex), `EmbeddedRuntime.kt`, `SetupPipeline.kt`, `RootfsDownloader.kt`, `ProotCommand.kt`, `OpenCodeConfig.kt` (merge `opencode.json`), `GuestFiles.kt` |
| `serve/` | `ServeHealth.kt`, `ServeCredentials.kt`, `PortAllocator.kt`, `RemoteOrigin.kt` |
| `backend/` | `Termux.kt` (RUN_COMMAND + PendingIntent), `TermuxScripts.kt` (serve/stop скрипты), `TermuxServe.kt`, `TermuxInstall.kt` |
| `phone/` | `PhoneHandlers.kt`, `PhoneApiService.kt`, `UnixSocketServer.kt`, `LocationPlan.kt` |
| `plugins/` | `Registry.kt` (реестр, имена, шаблоны заголовков), `PluginsController.kt`, `McpApi.kt`, `Installed.kt` |
| `update/` | `AppUpdater.kt`, `UpdateDownload.kt` |
| `prefs/` | `AppPrefs.kt`, `SecretPrefs.kt` (AES-GCM, Keystore) |
| `ui/` | `Screens.kt`, `PluginsScreen.kt`, `TermuxUi.kt`, `OpenCodeWebView.kt`, `WebViewSupport.kt`, `StatusText.kt` |
| `usb/` | `UsbController.kt` (список OTG, USB-serial) |

Ассеты: `assets/phone/` (клиент Phone API), `assets/termux/termux-install.sh` (копия `scripts/`, тест проверяет, что они совпадают). Документы: `README.md` / `README.en.md`, `CHANGELOG.md`, `CHANGES.md`, `TESTPLAN.md`.

## Сборка и тесты

```
export JAVA_HOME=<JDK 17> ANDROID_HOME=<Android SDK, build-tools 36>
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
# тестовые APK для проверки обновлений:
./gradlew :app:assembleDebug -PocmUpdateTestServer=http://10.0.2.2:8765 -PocmVersionCode=N -PocmVersionName=X
scripts/make-release.sh --apk <apk> --out <dir>   # APK + .sha256 + манифест, ничего не публикует
```

Unit-тесты (JUnit, 117 шт.) должны проходить. Termux-скрипты в тестах гоняются в настоящем bash. На эмуляторе проверяй обновление предыдущей версии через экран обновлений (данные и `opencode.json` должны сохраниться), экран «Плагины», циклы Start/Stop. Эмулятор держи один, с `-memory 2048..3072`: на хосте мало RAM и диска. Build-кэши и временные файлы чисти за собой.

## Правила

- Подписывай **тем же debug-ключом**, что и 0.1.0–0.2.1 (сертификат SHA-256 `65b96eb0…2a90`). Без него обновление поверх установленной версии не встанет. Путь к ключу задаётся в `local.properties` (`debug.keystore`) или в `OPENCODE_DEBUG_KEYSTORE`.
- Ключи, пароли, `local.properties` и `build/` **не коммитить** и не выводить в логи.
- **Без согласия владельца не пушить**, не создавать релизы и теги и не менять remote. Коммиты делай только локально.
- Тексты в UI пиши на двух языках через `tr("ru", "en")`. Правки `opencode.json` делай только слиянием и атомарной записью 0600.
- Честно разделяй, что проверено на устройстве или эмуляторе, а что не проверено.

## Известные ограничения и баги

- Termux: на x86_64 Android seccomp убивает glibc-загрузчик (SIGSYS, код 159). Полный запуск настоящего opencode в Termux на arm64 не проверен, проверялась только обвязка с фальшивым opencode.
- OAuth для MCP открывается во внешнем Chrome. На старом Chrome эмулятора `app.notion.com` выдаёт unsupported-browser, вход не проверен.
- Android 8–11 не проверены. На Android 12 с WebView 91 веб-UI не открывается: нужен WebView 126+.
- Серверы npx/Node, uvx/PyPI, Docker и SSE-only в каталоге помечены как недоступные.
- В режиме Remote приложение не видит `opencode.json` сервера: установка из «Плагинов» действует до перезапуска сервера.
- Сборки debug, сертификат debug.

## План следующих этапов

1. **OAuth через Custom Tabs**: вход MCP-серверов через Chrome Custom Tabs с возвратом по redirect в приложение вместо внешнего браузера и веб-UI.
2. **Node и uv в rootfs**: ставить Node.js (npx) и uv (uvx) в Ubuntu по запросу, чтобы запускать локальные MCP-серверы из реестра.
3. **Браузер для агента**: управляемый браузер (Chromium/Playwright в rootfs или мост к WebView) как MCP-инструмент.
4. **USB fd**: передавать файловый дескриптор USB-устройства (после разрешения Android) в гостевую систему, чтобы гостевые программы работали с OTG-устройствами напрямую, а не только через USB-serial.
5. **Стабильный ключ**: перейти с debug-ключа на постоянный release-ключ (хранение вне репозитория, план миграции: одна переустановка или ротация через APK Signature Scheme v3).
