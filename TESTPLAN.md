# План проверки OpenCode Mobile 0.2.1-debug (Termux, автообновление, плагины)

APK: `opencode-mobile-0.2.1.apk` (debug, сертификат `65b96eb0…2a90`, versionCode 11). Ставится поверх 0.2.0 (`-r`).
Тестовые APK лежат в `test-apks/`: `opencode-mobile-0.2.1-testserver.apk` (vc 11) и `opencode-mobile-0.2.2-test.apk` (vc 12, 0.2.2-test).
Обе сборки берут обновления с сервера `http://10.0.2.2:8765`. Нужен ещё `0.2.0/test-apks/opencode-mobile-0.2.0-testserver.apk`. Ничего не публиковать.

Результаты разработчика: эмулятор ocm-update35 (Pixel, API 35, x86_64, `-memory 3072`), 15.11.2026, 17:40–19:00 (UTC+7).
Номера в скобках — скриншоты из `screenshots/`. ✅ = проверено, ⚠️ = проверено частично, ⬜ = не проверено.

## Сервер обновлений с докачкой

Сервер должен отдавать `Accept-Ranges` и `206`. Простой `python3 -m http.server` Range не поддерживает, поэтому для пунктов 5–6 нужен свой сервер. Можно взять любой с поддержкой Range и ограничением скорости, чтобы успеть оборвать загрузку.
```
scripts/make-release.sh --apk test-apks/opencode-mobile-0.2.1-testserver.apk \
   --download-base http://10.0.2.2:8765/releases/download/v0.2.1 --out srv/releases/download/v0.2.1
cp srv/releases/download/v0.2.1/opencode-mobile-version.json srv/releases/latest/download/
```

## A. Автообновление (регрессия и run4)

1. ✅ 0.2.0-testserver с данными (`opencode.json`: phone и deepwiki-user; в prefs `remote_password` открытым текстом) → «Проверить обновления» → 0.2.0 (10) → 0.2.1 (11) (20) → «Скачать» → системное «Update». Итог: vc 11, `firstInstallTime` прежний, sha256 `opencode.json` прежний (`20e5194a…`). APK на устройстве совпадает с опубликованным, sha256 сверен. Прогнано на финальной сборке.
2. ✅ Пароль Remote: после первого запуска 0.2.1 в prefs только `remote_password_enc` (`v1:…`), открытого `remote_password` нет. В режиме Remote кнопка «Показать» выводит исходный пароль.
3. ✅ Тап по подписи «Проверять обновления при запуске…» переключает `update_auto_check` false/true (22).
4. ✅ Обрыв сети на 0.2.1 → 0.2.2-test (`svc wifi/data disable` во время загрузки): видно «Загрузка не удалась: Software caused connection abort. «Повторить» продолжит с места обрыва.» (23). Файл `.part` (5,7 МБ) остался.
5. ✅ Сеть обратно → «Повторить» → в журнале сервера `Range=bytes=5701632-` и ответ `206`, sha256 файла совпал, открылось системное «Update» (24).
6. ✅ Cancel → «Файл проверен» → «Позже» → файл удалён из `cache/app-update` → «Проверить обновления» показывает «Скачать», а не «Файл проверен» (25). Испорченный файл (1 байт) ведёт себя так же.
7. ✅ 0.2.1 → 0.2.2-test до конца на финальной сборке: vc 12, `opencode.json` прежний (34).

## B. Termux (run3)

8. ✅ Режим Termux: блок с шагами, «Скопировать команду установки», «Открыть Termux», «Разрешить запуск команд в Termux» (05, 06, 11). После выдачи разрешения кнопка разрешения исчезает.
9. ✅ Start без `allow-external-apps`: под кнопкой и в `termux.log` появляется текст ошибки Termux (`RunCommandService requires allow-external-apps…`) (07). Это путь PendingIntent. В 0.2.1 текст очищен от markdown и дополнен подсказкой.
10. ✅ Копирование: долгое нажатие в Termux → Paste → Enter (08, 09). Скрипт работает на свежем Termux 0.118.3 x86_64: glibc-repo, glibc-runner, patchelf, загрузка `opencode-linux-x64.tar.gz`, sha256 ok, patchelf, лаунчер, allow-external-apps (10, 12). Найденная проблема: на свежем bootstrap `patchelf` падал с `CANNOT LINK EXECUTABLE … __hash_memory` (старый libc++). Исправлено: скрипт ставит `libc++` и проверяет, что `patchelf` запускается.
11. ⚠️ `opencode --version` на x86_64 Android: `Bad system call` (SIGSYS, 159). Strace показал `access()` в glibc ld.so, его блокирует seccomp приложений на x86_64. Скрипт и приложение теперь называют причину. Start из приложения показывает «opencode в Termux не запускается…» (12). Полноценный opencode в Termux на arm64 **не проверен**. Попытка поставить arm64 Termux через трансляцию не удалась: bootstrap не встал.
12. ✅ Обвязка serve/stop с фальшивым `opencode` (python в Termux, отвечает на `/api/info` только с Basic-паролем из `OPENCODE_PASSWORD`): Start из приложения → `RUNNING — Termux 127.0.0.1:<порт>`, WebView показывает страницу фальшивого сервера (13). Значит пароль дошёл через stdin и файл. В `ps` аргументы без пароля. `~/.opencode-mobile` 0700, файлы 0600 (14).
13. ✅ Stop при запущенном «чужом» `opencode serve --port 4999`: наш процесс завершён, чужой жив, `serve.env` удалён (15), в `termux.log` есть `stop (port …): stopped` (`termux.log-excerpt.txt`). Повтор на финальной сборке прошёл.

## C. Встроенный режим и мелочи run3

14. ✅ Статус: «RUNNING — Сервер слушает 127.0.0.1:46657», порт один раз. Пустой статус выводится как «IDLE» (28).
15. ✅ Три цикла: «Стоп» → сразу после «Остановлено» «Запустить» → RUNNING каждый раз. Во время STOPPING кнопка «Запустить» неактивна (так задумано).
16. ⬜ Ошибка Setup один раз: проверено только юнит-тестом (`StatusTextTest`).
17. ⬜ `termux-location` со свежей точкой (`geo fix`): только юнит-тест `LocationPlanTest`, на устройстве не проверялось.

## D. Плагины (run5)

18. ✅ `ai.smithery/smithery-notion`: под полем Authorization написано «Будет записано как: Bearer ‹ваш ввод›» (30). Ввод `fake-key-021` → в `opencode.json` записано `"Authorization": "Bearer fake-key-021"`.
19. ✅ Установленный сервер с ключом и статусом `needs_auth`: «Сервер не принял ключ…», кнопки «Сменить ключ» и «Удалить», кнопки «Войти» нет (31). «Сменить ключ» (32) → `fake-key-022` → в файле `Bearer fake-key-022`, «Ключ … обновлён». Ключей в `host/logs` нет.
20. ✅ `com.notion/mcp`: в каталоге и в карточке название `notion`, имя по умолчанию `notion` (29, 33).
21. ✅ Удаление smithery-notion: `opencode.json` по содержимому равен исходному.
22. ✅ Фильтр каталога «Скрыть то, что телефон пока не запустит…» переключается тапом по подписи (checked true/false/true).
23. ✅ Регрессия «Плагины»: установленные phone и deepwiki-user подключены, каталог реестра грузится (26, 27).
24. ⬜ Remote: тексты «Нет в opencode.json» и диалог удаления на устройстве не проверены, нужен свой `opencode serve`. Логика: пометка только во встроенном режиме.
25. ⬜ Сообщение для WebView 91: только юнит-тест.

## Не проверено

- Настоящий arm64-телефон, Termux с настоящим opencode, Android 8–12.
- OAuth-вход до конца, реальный ключ Smithery.
- Remote-режим в «Плагинах» после правок run5.
- Публикация на GitHub Releases (ничего не публиковалось).
