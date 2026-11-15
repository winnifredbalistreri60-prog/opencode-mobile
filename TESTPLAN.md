# План проверки OpenCode Mobile 0.1.3-debug (автообновление)

APK: `opencode-mobile-0.1.3.apk` (debug-подпись, сертификат `65b96eb0…2a90`). Ставится поверх 0.1.2 с `-r`.
Тестовые APK лежат в `test-apks/`: `opencode-mobile-0.1.3-testserver.apk` (та же 0.1.3, но сервер обновлений
`http://10.0.2.2:8765`), `opencode-mobile-0.1.4-test.apk` (versionCode 5, правильная подпись),
`opencode-mobile-0.1.5-foreign-signed.apk` (versionCode 6, чужой ключ). Не публиковать.

## Локальный сервер обновлений

```
mkdir -p srv/releases/latest/download srv/releases/download/v0.1.4-test srv/releases/download/v0.1.5 \
         srv/api/repos/winnifredbalistreri60-prog/opencode-mobile/releases
scripts/make-release.sh --apk test-apks/opencode-mobile-0.1.4-test.apk \
   --download-base http://10.0.2.2:8765/releases/download/v0.1.4-test --out /tmp/good
cp /tmp/good/opencode-mobile-0.1.4-test.apk srv/releases/download/v0.1.4-test/
cp /tmp/good/opencode-mobile-version.json srv/releases/latest/download/
cd srv && python3 -m http.server 8765
```

Из эмулятора хост виден как `10.0.2.2`.

## Сценарии

1. **0.1.2 → 0.1.3 (обычная сборка, GitHub).** `adb install -r opencode-mobile-0.1.3.apk` поверх 0.1.2 с
   установленной средой. Среда на месте (экран сессии, не Setup). В «Настройки приложения → Обновления приложения»
   видно «Версия 0.1.3 (4). Источник: github.com/winnifredbalistreri60-prog/opencode-mobile». Пока репозитория нет,
   «Проверить обновления» даёт «HTTP 404: релиз или файл версии не найден…», автопроверка молчит.
2. **Автопроверка при запуске.** Поставить `…-testserver.apk` (-r). Сбросить `update_last_check` (или подождать 6 ч),
   открыть приложение: диалог «Обновление OpenCode Mobile 0.1.4-test» появляется сам.
3. **Битый sha256.** В манифесте заменить `sha256` на 64 нуля → «Скачать» → «Обновление отклонено: sha256 не совпадает…».
   В `cache/app-update` файла не остаётся.
4. **Чужая подпись.** Манифест на `0.1.5-foreign` с верным sha256 → «Обновление отклонено: APK подписан другим ключом».
5. **Запасной путь через API.** Удалить `releases/latest/download/opencode-mobile-version.json`, положить JSON релиза
   (`assets[].name == "opencode-mobile-version.json"`, `browser_download_url`) в
   `api/repos/winnifredbalistreri60-prog/opencode-mobile/releases/latest` → проверка находит 0.1.4-test.
6. **Разрешение «из этого источника» (баг MURF).** Без разрешения: «Скачать» → «Файл проверен. Разрешите установку…»,
   кнопка «Открыть настройки» → системный экран → включить «Allow from this source» → Назад. Кнопка диалога сама
   стала «Установить».
7. **Отмена и установка.** «Установить» → в окне Android «Cancel» → диалог снова «Установить» (без скачивания).
   «Установить» → «Update» → `versionName=0.1.4-test`, `versionCode=5`. Приложение закрывается (это нормально для
   самообновления), при запуске среда на месте, Start открывает веб-UI.
8. **Нет новее.** На 0.1.4-test «Проверить обновления» → «У вас последняя версия».
9. **Троттлинг и выключатель.** Повторный запуск в пределах 6 ч не делает запросов. Выключить автопроверку, сбросить
   `update_last_check`, запустить: запросов к серверу нет.

## Не проверено на эмуляторе (нужно на реальном устройстве или после публикации)

- Настоящий GitHub: редирект `releases/latest/download` → `release-assets.githubusercontent.com`, лимит API 60 запросов/ч.
- Обновление с запущенным встроенным сервером (остановка перед установкой).
- Обрыв сети посреди скачивания APK (ожидается «Обновление отклонено…» и «Повторить»).
- Android 8–12 (эмулятор: API 35) и arm64.
