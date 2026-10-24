# universityschedule

Приложение **universityschedule** для расписания занятий на Android и iOS, созданное на Kotlin Multiplatform и Compose Multiplatform. Для iPhone и iPad также доступна устанавливаемая PWA. Расписание и личные данные хранятся на устройстве; опубликованные файлы расписаний загружаются из GitHub только после действия пользователя.

## Возможности

- Расписания поддерживаемых групп и преподавателей.
- Локальная работа и офлайн-доступ после первой загрузки.
- Обновление по запросу с сохранением пользовательских пар, заметок, оценок и emoji-стикеров.
- Оценка каждой пары от 1 до 5 звёзд и выбор emoji-стикеров кнопкой «+».
- Календарь до окончания сессии плюс неделя; даты можно настроить для вуза или профиля.
- Ручные и повторяющиеся пары, обнаружение конфликтов, дедлайны и напоминания.
- Ежедневные сообщения из `content/daily-messages.txt`.

Клиенты не используют backend и не обращаются напрямую к сайтам вузов: Android и PWA читают опубликованные датасеты из `schedule-data` на GitHub Pages. Сайты вузов опрашиваются только при явном ручном запуске импортёра; кнопка **«Обновить расписание»** в приложении загружает уже опубликованный датасет и не запускает импорт.

## Подробно: обновить расписания в `arena/a130e7c0-bru-schedule`

Импортёр берёт опубликованные расписания с официальных источников, разбирает их существующими KMP-парсерами и обновляет `schedule-data/`. `all` обновляет оба университета (`bru` и `bsu`), `bru` или `bsu` — только выбранный датасет. При временной сетевой ошибке подтверждённый ранее файл сохраняется; новые профили публикуются только если в расписании удалось разобрать занятия.

### Важно перед запуском

- Запускайте импорт именно для ветки `arena/a130e7c0-bru-schedule`, если хотите записать результат туда. Workflow коммитит датасеты от имени GitHub Actions и отправляет их в выбранную ветку, а не автоматически в `master`.
- GitHub показывает ручной запуск `workflow_dispatch` только если файл workflow присутствует в default branch репозитория. В текущем состоянии `.github/workflows/import-schedule-data.yml` есть в `arena/a130e7c0-bru-schedule`, но отсутствует в default-ветке `master`, поэтому ручной запуск может не отображаться в Actions. Для работы с Arena-веткой без изменения `master` используйте локальный запуск ниже.
- **Публикация Pages общая.** `.github/workflows/deploy-pages.yml` срабатывает на изменения `schedule-data/` как в `arena/a130e7c0-bru-schedule`, так и в `master`, и деплоит в одно и то же окружение GitHub Pages. Поэтому push из Arena-ветки может заменить содержимое публичного сайта. Запускайте импорт/публикацию из этой ветки только если это ожидаемо; не переключайте ветку на `master` ради обхода ограничения запуска workflow.

### Вариант A — GitHub Actions, когда workflow доступен в Actions

1. Откройте репозиторий на GitHub и вкладку **Actions**.
2. Выберите **Import university schedule datasets** и нажмите **Run workflow**.
3. В поле ветки выберите ровно `arena/a130e7c0-bru-schedule`. Не выбирайте `master`: workflow запишет результат в выбранную ветку.
4. В параметре **University datasets to refresh** выберите `all` для обоих вузов, `bru` только для БРУ или `bsu` только для БГУ.
5. Нажмите **Run workflow** и дождитесь завершения всех шагов. Не запускайте второй импорт, пока первый ещё выполняется.
6. Откройте лог шага импорта и проверьте итоговые числа `discovered`, `imported`, `preserved` и `skipped`. В шаге проверки должны успешно пройти Python-тесты и валидация датасетов.
7. Проверьте `schedule-data/import-report.json` в результате ветки: там указаны выбранные вузы, счётчики и причины ошибок/пропусков. Убедитесь, что workflow создал commit в `arena/a130e7c0-bru-schedule` (или сообщил, что изменений для публикации нет).
8. После push дождитесь workflow **Deploy iOS web app to GitHub Pages**, если требуется опубликовать данные на сайте. Помните о предупреждении выше: это общее Pages-окружение.

### Вариант B — запустить импорт локально прямо в Arena-ветке

Потребуются JDK 21, Android SDK Platform 37, Python 3 и доступ к официальным сайтам. Выполняйте команды из корня чистой рабочей копии, чтобы случайно не включить посторонние изменения в коммит.

```bash
git switch arena/a130e7c0-bru-schedule
git pull --ff-only origin arena/a130e7c0-bru-schedule
git status --short
```

Если `git status` показывает чужие незакоммиченные изменения, сначала сохраните их отдельно. Затем запустите импорт для обоих вузов (для одного замените `all` на `bru` или `bsu`):

```bash
SCHEDULE_DATA_IMPORT=true \
SCHEDULE_DATA_UNIVERSITY=all \
SCHEDULE_REPOSITORY_ROOT="$PWD" \
SCHEDULE_DATA_ROOT="$PWD/schedule-data" \
bash gradlew :androidApp:testDebugUnitTest \
  --tests com.example.bruschedule.tools.ScheduleDataImporterTest \
  --no-daemon
```

Проверьте результаты до публикации:

```bash
python3 -m unittest discover -s tools/schedules/tests -v
python3 tools/schedules/validate_schedule_data.py
python3 -m json.tool schedule-data/import-report.json
git diff --check
git status --short
```

Просмотрите `schedule-data/import-report.json`: при `skipped_profiles` больше нуля проверьте массив `failures` и убедитесь, что пропуски ожидаемы. Если официальный сайт изменил формат и нужный профиль не разобрался, не редактируйте сгенерированные CSV вручную: сообщите ссылку на официальный источник (или приложите HTML/ZIP), чтобы обновить парсер и тестовый fixture.

Если diff содержит только проверенные файлы `schedule-data/`, зафиксируйте и отправьте изменения **только в Arena-ветку**:

```bash
git add schedule-data/
git commit -m "Refresh university schedule datasets"
git push origin arena/a130e7c0-bru-schedule
```

После публикации пользователю не нужно переустанавливать приложение: в `universityschedule` откройте настройки и нажмите **«Обновить расписание»** для нужного профиля. Личные пары, заметки, оценки и другие локальные данные остаются на устройстве. Фонового опроса сайтов вузов нет.

## Android

Требуются JDK 21 и Android SDK Platform 37. Откройте корень репозитория в Android Studio и дождитесь Gradle Sync. Для локальной проверки выполните:

```bash
./gradlew :shared:allTests
./gradlew :androidApp:installDebug
```

Debug APK можно собрать командой `./gradlew :androidApp:assembleDebug`.

## iOS

### Нативное приложение

Для сборки нужны macOS, Xcode и JDK 21. Откройте `iosApp/iosApp.xcodeproj`, выберите схему `iosApp` и симулятор iPhone, затем запустите приложение кнопкой **Run**. Для установки на физическое устройство задайте Apple Team ID в `iosApp/Configuration/Config.xcconfig` и настройте подпись в Xcode. Bundle ID: `com.konstantinchvei.universalschedule`.

### Установка веб-версии на iPhone или iPad

Откройте в Safari опубликованный сайт: [universityschedule](https://konstantin-chvei.github.io/BRU-schedule/). Нажмите **Поделиться → На экран Домой → Добавить**; если Safari предлагает переключатель **Открывать как веб-приложение**, включите его. Затем запускайте приложение с появившегося значка. Для офлайн-доступа сначала откройте приложение при подключении к интернету.

Полная инструкция доступна [на сайте](https://konstantin-chvei.github.io/BRU-schedule/ios-install.html) и по кнопке **«Установка на iPhone / iPad»** внутри веб-версии.
