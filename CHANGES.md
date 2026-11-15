# OpenCode Mobile 0.3.0-debug: Hardware API

Неофициальный Android-клиент OpenCode (не продукт команды OpenCode).
Пакет: `ru.dredd20.opencode`. versionName **0.3.0**, versionCode **12**.
Подпись: тот же debug-сертификат, что у 0.1.0–0.2.1
(SHA-256 `65b96eb07abe416771b1a8c96184d87641d2e1cf51954ec340d40d34b32d2a90`). Ключа в репозитории нет.
Ветка `feature/hardware` от `455d27b` (0.2.1). Termux:API-совместимые команды сохранены.

## Hardware API

Собственный слой поверх Phone API (unix-сокет, CLI `termux-*` / `hw-session` / `bluetooth` / `net`, MCP `phone`).
Каждая функция — команда сокета, CLI-обёртка и инструмент phone-mcp. Разрешения проверяются, ошибки понятные.
Новые категории в настройках: Bluetooth, Wi-Fi подключение, сессии с железом, NFC (выкл. по умолчанию).
Камера и геолокация (включая Wi-Fi scan) по-прежнему выкл. по умолчанию.

### USB Host

Расширен `UsbController` (прежний USB-serial CDC/CH340/CP210x/FTDI сохранён).
`usb list` отдаёт интерфейсы и конечные точки. `usb permission <device>` запрашивает системный диалог
(приёмник `UsbPermissionReceiver`). `usb open/write/read/close` — serial. Добавлены `usb hid-read` / `usb hid-write`
(interrupt или SET/GET_REPORT) и `usb transfer` (bulk, interrupt, control).

### Сессии с железом

`hw-session open|send|read|expect|log|close`. Транспорты: `usb-serial`, `bt-spp`, `tcp`.
`expect` матчит regex (или литерал) по накопленному буферу, хвост не теряется.
Журнал сессии пишется в `/opencode-host/hardware/sessions/<id>.log`.

Прошивка ESP32 через esptool по OTG **не входит в 0.3.0**: в rootfs нет готового esptool, а надёжный прогон
на железе здесь не из чего проверить. Это следующий этап (поставить esptool/Python в среду и прогнать на arm64
с платой). Сессию `open` + `send`/`expect` для AT и отладочных команд уже можно использовать.

### Bluetooth

BLE: scan, connect, список GATT, read/write, notify start/stop/wait, disconnect.
Классический SPP (RFCOMM): connect/send/read/close.
Разрешения Android 12+ (`BLUETOOTH_SCAN` без локации, `BLUETOOTH_CONNECT`) и старые (`BLUETOOTH`,
`BLUETOOTH_ADMIN`, геолокация для сканирования).

### Wi-Fi и сеть

`termux-wifi-scaninfo` учитывает троттлинг Android (обычно не больше 4 сканирований за 2 минуты) и тогда
возвращает последние известные сети с флагом `throttled`. `wifi-connectioninfo` как раньше.
Подключение: `wifi-connect` через `WifiNetworkSpecifier` (системное подтверждение, Android 10+) или
`--suggestion`. Диагностика `net ping|dns|port|mdns|tcp|udp`. mDNS через `NsdManager`.

### NFC

Чтение NDEF и информации о метке, ISO-DEP/NfcA `transceive`, запись NDEF (с подтверждением).
Только на переднем плане: `enableReaderMode`, ожидание метки с таймаутом. Если приложения нет на экране,
оно поднимается. Без поднесённой метки команда честно завершается ошибкой.

### Камера

CameraX: `camera-capture` (кадр по запросу), `camera-start` / `camera-stop` / `camera-latest`.
Непрерывный захват — foreground-сервис с видимым уведомлением, JPEG в каталог проекта, кольцевой буфер
(по умолчанию 8 файлов). `termux-camera-photo` (системная камера) сохранён.

## Тесты

132 unit-теста, 0 ошибок (было 117). Новые: разбор hex/разрешения/таймаутов, логика сессии (`expect` по кускам, хвост, журнал, закрытие),
кольцевой буфер камеры, короткие UUID Bluetooth, список инструментов phone-mcp и категории протокола.

## Что не проверялось

На этой машине нет USB OTG, BLE/SPP-периферии, NFC-меток и настоящей камеры устройства.
Команды собираются и отвечают понятными ошибками без железа (пустой список USB, «Bluetooth выключен»,
«NFC нет», и т. п.), но живой обмен с ESP32, HID, меткой и потоком кадров на телефоне не прогонялся.
Прошивка esptool — следующий этап. Android 8–11, реальный Termux на arm64 и OAuth по-прежнему не проверялись.
