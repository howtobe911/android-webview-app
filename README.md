# GraFit Android WebView App

Полноценный Android Gradle-проект для обёртки сайта `https://second.risedie.ru/web` в WebView
с нативным bridge для Health Connect.

## Что уже есть
- WebView-контейнер под твой сайт
- JS bridge `ChallengeAppBridge`
- запрос разрешений Health Connect
- чтение шагов и дистанции за сегодня
- безопасный allowlist доменов
- открытие внешних ссылок вне WebView

## Что сайт должен уметь вызывать
Внутри страницы доступны методы:
- `window.ChallengeAppBridge.getBridgeInfo()`
- `window.ChallengeAppBridge.requestActivityPermissions()`
- `window.ChallengeAppBridge.getActivitySyncPayload()`

После завершения окна разрешений Android шлёт в страницу событие:
- `challengeapp:permissions-changed`

## Как собрать
### Android Studio
1. Открыть папку `android-webview-app`
2. Дождаться sync Gradle
3. Build APK

### В облаке
- проект можно грузить как обычный Android Gradle app
- главный модуль: `app`
- assemble task: `app:assembleDebug` или `app:assembleRelease`

## Что менять под свой домен
Файл: `app/build.gradle.kts`
- `APP_WEB_URL`
- `APP_ALLOWED_HOSTS_JSON`


## 2026 build baseline
- AGP 9.0.1
- Gradle 9.1.0
- JDK 17
- compileSdk / targetSdk 36
- Health Connect client 1.1.0-rc03
- Built-in Kotlin (do not apply org.jetbrains.kotlin.android plugin)
