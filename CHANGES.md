# OpenCode Mobile 0.1.3-debug — автообновление из GitHub Releases

Неофициальный Android-клиент OpenCode (не продукт команды OpenCode).
Пакет: `ru.dredd20.opencode`. versionName **0.1.3**, versionCode **4**.
Подпись: тот же debug-сертификат, что у 0.1.0–0.1.2
(SHA-256 `65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90`). Ключа в репозитории нет.

## Что нового

### Автообновление (`app/src/main/java/ru/dredd20/opencode/update/`)

- Источник: `https://github.com/winnifredbalistreri60-prog/opencode-mobile/releases/latest/download/opencode-mobile-version.json`.
  Если он недоступен, берётся публичный `https://api.github.com/repos/winnifredbalistreri60-prog/opencode-mobile/releases/latest`
  и из него URL ассета `opencode-mobile-version.json`. Токенов нет.
- Манифест: `versionCode`, `versionName`, `url`, `sha256`, `size`, `minSdk`, `notes`. URL APK принимается только
  с префиксом `https://github.com/winnifredbalistreri60-prog/opencode-mobile/releases/download/`, без `..`, `?`, `#`, `@`.
  Редирект с https на http запрещён.
- Проверка при запуске не чаще раза в 6 ч (переключатель «Проверять обновления при запуске»), кнопка
  «Проверить обновления» в «Настройки приложения → Обновления приложения». Сравнение только по `versionCode`.
  «Позже» скрывает эту версию от автопроверки, ручная проверка показывает её снова.
- Скачивание в кэш приложения с прогрессом. Проверки: размер, sha256 (сравнение в постоянное время),
  пакет `ru.dredd20.opencode`, `versionCode` равен манифесту и больше установленного, сертификат подписи APK совпадает
  с установленным. Любое несовпадение: файл удаляется, в диалоге «Обновление отклонено: …».
- Установка через `PackageInstaller` (сессия + системное подтверждение). Перед установкой sha256 пересчитывается,
  встроенный сервер останавливается.
- Баг MURF учтён: разрешение «Установка неизвестных приложений» перечитывается при каждом возврате в приложение
  (`onResume` + `LifecycleEventEffect(ON_RESUME)`), кнопка диалога сама меняется с «Открыть настройки» на «Установить».
- Отмена в системном окне установки возвращает диалог в состояние «Установить» без повторного скачивания.
- Журнал обновлений: `update.log` (виден в «Показать журнал»).

### Сборка и выпуск

- `scripts/make-release.sh`: сборка (`testDebugUnitTest` + `assembleDebug`), проверка сертификата `65b96eb0…`,
  sha256, генерация `opencode-mobile-version.json`. Ничего не публикует. Отказывается выпускать тестовые сборки
  с локальным сервером обновлений и APK с чужой подписью.
- Тестовые свойства Gradle: `-PocmUpdateTestServer=http://10.0.2.2:8765` (манифест, API и APK с локального
  HTTP-сервера, в диалоге видна плашка TEST SERVER), `-PocmVersionCode`, `-PocmVersionName`. Обычная сборка
  всегда смотрит на GitHub.
- `REQUEST_INSTALL_PACKAGES` в манифесте, ресивер `UpdateInstallReceiver` (не экспортирован).

### Мелочи

- Русский текст ошибки установки: «не удалось скачать …» вместо «не скачать …».
- README (ru/en) и CHANGELOG: раздел про обновление и выпуск релиза.

## Тесты

68 юнит-тестов (было 51). Новые в `UpdateManifestTest`: разбор манифеста, отбраковка плохих sha256/URL/versionCode,
усечение notes, тестовый префикс, fallback через API, сравнение versionCode, «Позже», троттлинг 6 ч, sha256
(известные векторы и поток), сравнение дайджестов, совпадение подписантов, состояние кнопки «Установить».
