Русский | [English](README.md) | [简体中文](README_zh_CN.md)

# AsteriskNG

Клиент Xray для Android, работающий на базе [Xray-core](https://github.com/XTLS/Xray-core), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) и [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Telegram-канал

[Asterisk4Magisk](https://t.me/Asterisk4Magisk)

## Возможности

- Поддержка режимов работы: VPN Service, TPROXY (с ROOT-правами) и TUN2SOCKS (с ROOT-правами).
- Поддержка протоколов: VMess, VLESS, Trojan, Shadowsocks, Socks, HTTP, Hysteria2, WireGuard, а также групп стратегий (strategy groups) и цепочек прокси (chain proxy).
- Поддержка форматов подписок v2rayNG и mihomo.
- Управление файлами ресурсов: `geoip.dat`, `geosite.dat`, `geoip-only-cn-private.dat` и исполняемым файлом Xray.
- Генерация скрипта автозапуска с ROOT-правами при загрузке системы через директорию `service.d` в Magisk.
- Интерфейс на базе MIUIX Compose UI.

## Скриншоты

<p align="center">
  <img src="image/screenshot/5.jpg" width="24%" alt="Скриншот 1" />
  <img src="image/screenshot/6.jpg" width="24%" alt="Скриншот 2" />
  <img src="image/screenshot/7.jpg" width="24%" alt="Скриншот 3" />
  <img src="image/screenshot/8.jpg" width="24%" alt="Скриншот 4" />
</p>

## Режимы работы

### VPN Service

- Работает без ROOT-прав.
- Использует стандартный системный класс Android `VpnService`.
- Подходит для обычного использования в качестве VPN на уровне приложений Android.

### TPROXY (ROOT)

- Требуются ROOT-права.
- Запускает локальный исполняемый файл Xray напрямую с помощью библиотеки `libsu`.
- Использует `iptables` и политику маршрутизации (policy routing) для прозрачного проксирования трафика.
- В качестве входящего соединения (inbound) Xray использует настроенный порт прозрачного прокси.

### TUN2SOCKS (ROOT)

- Требуются ROOT-права.
- Запускает локальный исполняемый файл Xray напрямую с помощью библиотеки `libsu`.
- Использует инструмент `hev-socks5-tunnel` для создания фиксированного виртуального интерфейса (TUN) `asterisk0`.
- Использует локальный входящий порт SOCKS5 в Xray в качестве цели для туннеля.
- Разделяет большую часть логики ROOT-маршрутизации и проксирования приложений с режимом TPROXY, но направляет трафик через TUN-интерфейс вместо входящего порта TPROXY в Xray.

## Файлы ресурсов

- Файлы среды выполнения хранятся в приватной директории приложения `files/xray` (обычно это `/data/user/0/org.asterisk.zcc.ang/files/xray`).
- Встроенный исполняемый файл Xray восстанавливается из нативных библиотек. Его можно заменить вручную файлом `xray` или zip-архивом, содержащим `xray`.
- Файлы `geoip.dat` и `geosite.dat` могут быть восстановлены из встроенных ресурсов (assets), обновлены из онлайн-источников или заменены вручную.
- Встроенные источники обновлений включают в себя: [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat), [v2fly/geoip](https://github.com/v2fly/geoip), [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community), [Chocolate4U/Iran-v2ray-rules](https://github.com/Chocolate4U/Iran-v2ray-rules) и [runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat).

## Разработка (Сборка)

Перед сборкой проекта инициализируйте субмодули:

```bash
git submodule update --init --recursive
```

Откройте корневую папку проекта в Android Studio или соберите проект через Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

На macOS или Linux:

```bash
./gradlew assembleDebug
```

Процесс сборки:
- использует Android SDK и NDK;
- загружает или подготавливает встроенные ресурсы Xray-core;
- проверяет ветку/тег `hev-socks5-tunnel` на соответствие значению `ProjectConfig.HEV_SOCKS5_TUNNEL_VERSION` перед компиляцией;
- собирает нативную JNI-библиотеку `hev-socks5-tunnel` и консольную утилиту (CLI) из подключенного субмодуля;
- переключает `asteriskd`, `bpf2socks`, `bpfmatcher` и `setuidgid` на версии из `ProjectConfig`, после чего собирает их с помощью NDK;
- упаковывает нативные компоненты для архитектур `arm64-v8a`, `armeabi-v7a`, `x86` и `x86_64`.

Если Gradle не может найти Android NDK, укажите путь к нему в параметре `ndk.dir` внутри файла `local.properties`, задайте переменную окружения `ANDROID_NDK_HOME` или установите NDK через менеджер компонентов внутри Android SDK.

## Поддержка WSA (Windows Subsystem for Android)

Для использования в среде WSA разрешение на запуск VPN можно выдать вручную через ADB-команду:

```bash
appops set org.asterisk.zcc.ang ACTIVATE_VPN allow
```

## Лицензия

[GPL-3.0](LICENSE)

## Благодарности и используемые компоненты

- [@XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [@2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)
- [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [@topjohnwu/libsu](https://github.com/topjohnwu/libsu)
- [@compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)
- [@2dust/v2rayNG](https://github.com/2dust/v2rayNG)
- [@Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
- [@v2fly/geoip](https://github.com/v2fly/geoip)
- [@v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)
- [@Chocolate4U/Iran-v2ray-rules](https://github.com/Chocolate4U/Iran-v2ray-rules)
- [@runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat)
- [@mayaxcn/china-ip-list](https://github.com/mayaxcn/china-ip-list)
