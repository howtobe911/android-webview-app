# GraFit Android WebView

Основная оболочка проекта: Gradle, release/instrumentation, Health Connect и WorkManager.
Защиты WebView сохранены: HTTPS, точные origin, запрет файлового/content-доступа,
смешанного контента и сторонних cookies; API-запросы не следуют redirect.

Bridge использует AndroidX WebMessageListener. На каждом вызове проверяются native
sourceOrigin, isMainFrame, текущий URL, nonce документа и состояние сессии. Старый
addJavascriptInterface больше не используется. Без поддержки WEB_MESSAGE_LISTENER
приложение просит обновить System WebView и не включает небезопасный запасной мост.

Все методы ChallengeAppBridge теперь возвращают Promise<string>. Сайт из этого патча
ожидает результат через await. Вход и refresh не передают секреты в JS. Logout немедленно
закрывает доступ к сессии, отменяет синхронизацию и затем очищает credentials/cookies.
Блокирующие методы работают вне UI-потока; системные запросы разрешений — в UI-потоке.

Доверены только https://second.risedie.ru и https://www.second.risedie.ru, порт 443
или неуказанный, без userinfo. Для смены домена обновить нативную политику, BuildConfig
и bootstrap вместе. Внешние ссылки открываются только по пользовательскому переходу;
iframe и автоматический внешний redirect не получают bridge.

Разрешения Health Connect: чтение Steps, Distance, ExerciseSession. Они нужны для
существующей атомарной модели шагов и беговой дистанции. Запись и чтение истории не
добавлены. Отдельные разрешения физической активности, фонового чтения и уведомлений
сохранены. Частичное/неполное чтение не подменяется нулевым серверным снимком.
Повтор отправки сохраняет существующий зашифрованный пакет и idempotency key.
HTTP 408/425/429/5xx теперь допускают штатный повтор WorkManager.

## Сборка и проверка

Требуются Android SDK 36 и JDK 21 для поставленного в дампе wrapper (его класс
имеет bytecode 65). Java target приложения остаётся 17. AGP 9.0.1 / Gradle 9.1.0.
Обычный debug-вариант отключён; используйте instrumentation:

```sh
cd /var/www/proj2/app/current/android-webview-app
bash gradlew --no-daemon :app:assembleInstrumentation :app:testInstrumentationUnitTest
bash gradlew --no-daemon :app:connectedInstrumentationAndroidTest
```

Последняя команда требует подключённого тестового Android-устройства/эмулятора.
Тест SecureBridgeWebViewInstrumentedTest использует настоящий System WebView:
вызов из main frame, iframe с украденным nonce, отсутствие моста у внешнего origin,
отказ привилегированному вызову после logout. Использовать отдельную тестовую установку
и учётную запись: существующие instrumented-тесты credentials очищают тестовую сессию.

Выпуск: :app:assembleRelease с внешней конфигурацией подписи и новым versionCode.
Патч файлов на сервере не обновляет установленный APK. Нужна новая сборка приложения.

В среде подготовки патча Android SDK и JDK 21 отсутствовали. Попытка Gradle
остановилась до компиляции. Успешная сборка и прохождение device-тестов НЕ заявляются.
Полная матрица приёмки: ../mobile-bridge-tests/DEVICE_SCENARIOS.md.
