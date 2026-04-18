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

## Build prerequisites
- Use Java 21 for Gradle wrapper execution.
- Build this project as its own repository root, or keep the dynamic Codemagic path detection in place if the full monorepo is uploaded.
- Do not build on the production VPS; use Codemagic or a dedicated CI runner.
