# Сборка и распространение приложения «Расписание»

## Android APK

Для полной сборки проекта требуется JDK 21 и Android SDK Platform 37. В Android Studio проверьте настройки **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** (JDK 21), а в **SDK Manager** установите платформу API 37 и примите лицензии. Если API 37 отображается как preview, включите показ preview-пакетов.

1. Клонируйте репозиторий и откройте в Android Studio **корневую папку проекта** (где лежит `settings.gradle.kts`), а не папку `androidApp`.
2. Дождитесь завершения Gradle Sync.
3. Соберите устанавливаемый debug APK:
   - Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**;
   - Windows PowerShell из корня проекта:
     ```powershell
     .\gradlew.bat :androidApp:assembleDebug
     ```
   - macOS/Linux:
     ```bash
     ./gradlew :androidApp:assembleDebug
     ```
4. APK появится по пути:
   ```text
   androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

`assembleDebug` автоматически подписывает APK отладочным ключом, поэтому его можно передать одногруппникам для тестирования. На Android может потребоваться разрешить установку приложений из выбранного файлового менеджера/браузера.

Для длительного распространения и будущих обновлений используйте **Build → Generate Signed Bundle / APK → APK** и собственный keystore. Храните keystore и пароли отдельно и не добавляйте их в Git: обновления должны быть подписаны тем же ключом.

## iOS

Для установки на iPhone без Apple Developer Program выбран путь PWA, но web/PWA-цель пока не добавлена в проект. Инструкции ниже относятся к текущей нативной iOS-сборке.

Сборка iOS требует Mac с Xcode и JDK 21; Android Studio на Windows не может собрать IPA. Проект для iOS находится в `iosApp/iosApp.xcodeproj`.

- **Симулятор:** откройте Xcode-проект, выберите схему `iosApp` и iOS Simulator, затем запустите приложение. CI также собирает zip с `.app` для симулятора.
- **Физические iPhone одногруппников:** нужен подписанный IPA. Для распространения группе проще всего использовать TestFlight: требуется Apple Developer Program, App Store Connect и настройка подписи приложения. Альтернатива — Ad Hoc distribution с UDID каждого устройства и соответствующим provisioning profile. Неподписанный `.app` или IPA нельзя установить на iPhone.

Перед архивированием в Xcode задайте собственный уникальный Bundle Identifier и Apple Development Team в настройках подписи/`iosApp/Configuration/Config.xcconfig`. Сертификаты и provisioning profile не храните в Git и не отправляйте в чат; если понадобится CI-подпись, добавьте их как защищённые GitHub Actions Secrets.

## Текущие CI-сборки

Успешная сборка Android APK и iOS Simulator app доступна в разделе **Artifacts** запуска GitHub Actions:

<https://github.com/konstantin-chvei/BRU-schedule/actions/runs/39439156196>

В этой CI-среде Android SDK API 37 отсутствовал. Поэтому опубликованный там APK собран как предварительная тестовая сборка с API 34 и пропуском проверки AAR metadata. Для обычной сборки из Android Studio используйте Android SDK API 37, как описано выше. Артефакт iOS из этого запуска предназначен только для симулятора, не для установки на физический iPhone.
