# Сборка и выпуск

## Требования

- JDK 21;
- Android SDK Platform 37 и принятые лицензии;
- для нативной iOS-сборки — macOS и Xcode.

Откройте в Android Studio корень репозитория — каталог с `settings.gradle.kts` — и дождитесь завершения Gradle Sync.

## Проверка и Android

```bash
./gradlew :shared:allTests
./gradlew :androidApp:assembleDebug
```

Debug APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Он подписан отладочным ключом и предназначен только для проверки.

Идентификатор Android-приложения и iOS Bundle ID: `com.konstantinchvei.bruschedule`. Перед первой публикацией подтвердите, что это желаемый и доступный идентификатор в аккаунтах магазинов; после выпуска его нельзя менять без создания нового приложения.

Для релизного Android App Bundle выполните `:androidApp:bundleRelease` или используйте **Build → Generate Signed Bundle / APK** в Android Studio. Перед распространением подпишите сборку собственным keystore. Keystore и пароли храните вне Git; все обновления должны быть подписаны тем же ключом. Текущая CI-сборка не создаёт подписанный релиз.

## iPhone и iPad

Пользовательская версия для iOS — PWA в `web/`, устанавливаемая из Safari. Инструкция находится в [`IOS_WEB_APP.md`](IOS_WEB_APP.md) и доступна в настройках приложения.

Нативный проект `iosApp` оставлен для сборки и проверки общего кода. Его Bundle ID — `com.konstantinchvei.bruschedule`; перед подписанием укажите Apple Team ID в `iosApp/Configuration/Config.xcconfig`. Публикация нативной версии требует Apple Developer Program и подписывания в Xcode.

## CI и публикация PWA

Workflow сборки выпускает debug APK и приложение для iOS Simulator. Эти файлы предназначены для проверки, а не для публикации в магазинах. PWA публикуется отдельным workflow GitHub Pages; для общего доступа репозиторий и Pages должны быть доступны пользователям по HTTPS. Подробности — в [`IOS_WEB_APP.md`](IOS_WEB_APP.md).
