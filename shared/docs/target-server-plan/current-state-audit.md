# Аудит текущего приложения

## 1. Граница исследования

Исследован Android checkout (сейчас `APP/android`) на commit
`b34cd0e6f62071c8a1bae40c4b79e6d05a9b8d23`. Во время исследования другой
агент менял документацию и build-инфраструктуру, поэтому функциональные выводы
привязаны к указанному commit, а не к незакоммиченным файлам.

Код приложения не изменялся. Новые файлы этого исследования сейчас находятся в
`shared/docs/target-server-plan/`.

## 2. Что приложение умеет сейчас

### 2.1. Общий shell

В приложении три стабильных раздела:

1. `Overview` — профили, поиск BLE, подключение, replay, статистика и события.
2. `Charts` — выбор сенсора/канала, окно, follow-live, pan/zoom и fullscreen.
3. `Settings` — BLE transport profile, тема, язык и Google Drive backup.

Экспорт доступен из `Overview` и `Charts` через bottom sheet. Доступны общий
share, `packets.bin`, CSV, raw stream, diagnostic log и Google Drive.

Фактические точки:

- `AppShell.kt:62-86` — список разделов;
- `AppShell.kt:139-224` — `Scaffold`, top bar, bottom navigation и destination;
- `AppShell.kt:226-269` — export bottom sheet;
- `DeviceScreen.kt` — содержимое Overview/Charts/Settings.

### 2.2. Локальная модель

`WoonaDatabase` использует SQLite schema version 2 и хранит
`filesDir/Woona/woona.sqlite`.

Таблицы:

- `dog_profiles` — UUID, отображаемое имя/номер и вся анкета JSON;
- `recordings` — UUID, профиль, `live`/`replay`, статус, UTC-время, timezone,
  анкета сессии JSON и относительная директория;
- `artifacts` — UUID, запись, тип, относительный путь, размер и время создания.

Папка записи:

```text
filesDir/Woona/recordings/<profile-id>/<local-date>/<recording-id>/
```

В ней могут находиться:

```text
packets.bin
raw_fragments.binlog
diagnostics.log
channel.csv
video.mp4
sync.json
```

Положительные свойства:

- UUID и относительные пути пригодны для серверной миграции;
- включены SQLite foreign keys;
- путь канонизируется и проверяется от выхода из корня;
- `sync.json` пишется через временный файл и atomic move, если ФС поддерживает;
- незавершённые `preparing`/`recording` при старте переводятся в `interrupted`;
- экспорт читает snapshots, а не конкурирует с открытыми writer-файлами.

Фактические точки:

- `WoonaData.kt:359-425` — SQLite и DDL;
- `WoonaData.kt:427-438` — восстановление незавершённых записей;
- `WoonaData.kt:495-555` — создание и завершение записи;
- `WoonaData.kt:576-607` — атомарный `sync.json`;
- `WoonaData.kt:663-667` — защита относительного пути;
- `WoonaData.kt:723-748` — канонические artifact names и DDL.

### 2.3. Анкета собаки

Текущая анкета содержит:

- имя/номер, приют/место, породу и сходство метиса;
- размер, возраст и источник возраста;
- пол и стерилизацию;
- вес и источник веса;
- body condition score, мышечную массу, обхват шеи;
- шерсть, подшёрсток и выстриженные участки;
- наблюдаемые признаки, диагнозы и лекарства;
- содержание, прогулки, соседство с животными, разрешение и заметки.

Сейчас обязательным является только `numberOrName`. Все остальные Kotlin-поля
nullable или имеют пустой список по умолчанию. Кнопка Save проверяет только
непустое имя.

Дополнительные проблемы:

- `RadioButton` повторным нажатием снимает выбор, поэтому обязательный
  single-choice легко вернуть в пустое состояние;
- неверный текст в числовом поле тихо превращается через `toIntOrNull()` или
  `toDoubleOrNull()` в `null`;
- `observedSigns` не имеет явного `none`, поэтому пустой список неоднозначен:
  «нет симптомов» или «вопрос пропущен»;
- `diagnosesDetails` всегда доступен и никак не зависит от ответа о диагнозах;
- `resembles` всегда доступен и никак не зависит от типа породы;
- body condition использует одновременно nullable score и отдельный флаг
  `unable`, что усложняет единое правило обязательности;
- свободные `walks`, `sensorPosition` и другие поля создают несопоставимые
  варианты написания;
- длинный full-screen dialog не показывает прогресс по разделам и не переводит
  пользователя к первому ошибочному полю.

Фактические точки:

- `WoonaData.kt:19-47` — nullable-модель;
- `ProfilesUi.kt:222-404` — поля и единственная проверка имени;
- `ProfilesUi.kt:575-646` — общие поля без required/error-контракта;
- `ProfilesUi.kt:679-743` — тихая конвертация неверных чисел в `null`.

### 2.4. Анкета сессии

Текущая сессионная анкета содержит:

- автоматически сгенерированный UUID, показанный как «Номер сессии»;
- локальную дату и время начала;
- оператора;
- multi-choice `recordingContents`: `gait`, `activity`, `rest`, `other`;
- поверхность, indoor/outdoor, температуру воздуха;
- свободное положение сенсора и затяжку ошейника;
- состояние до измерения;
- пульс, дыхание, температуру тела и время измерения.

Подтверждённые проблемы:

1. `gait` является частным случаем activity, но обе галочки можно выбрать
   одновременно. Можно также выбрать `rest` вместе с ними. Значение не задаёт
   однозначный фактический протокол.
2. Выбор активности не меняет доступные значения и не очищает ставшие
   несовместимыми ответы.
3. `location=indoors` не ограничивает поверхность: можно сохранить indoor +
   grass/soil/asphalt. `outdoors` не ограничивает indoor floor/tile.
4. Все поля, кроме синтаксиса даты/времени, необязательны.
5. Неверные числа тихо становятся `null`.
6. Для replay имеется кнопка «Пропустить анкету»; запись сохраняется с
   `questionnaire_json = NULL`.
7. Дата и время берутся из ручного ввода и могут отличаться от реального
   времени нажатия Start. В CSV именно выбранное session start используется как
   абсолютный якорь.
8. `measurementTime` не валидируется, хотя `startTime` валидируется.
9. Нет диапазонов для температуры, пульса, дыхания, веса и размеров.

Фактические точки:

- `WoonaData.kt:49-65` — nullable-модель;
- `ProfilesUi.kt:407-507` — UI и единственная проверка даты/времени;
- `MainActivity.kt:417-440` — replay skip;
- `WoonaData.kt:337-347` — ручные дата/время превращаются в `startedAtUtc`;
- `BleSessionCsvExporter.kt:73-80` — CSV якорится на session start.

### 2.5. Жизненный цикл BLE-записи

Текущий live flow:

1. Пользователь сканирует BLE и выбирает адрес.
2. Открывается анкета сессии.
3. До подключения создаются запись `preparing` и локальная директория.
4. GATT подключается, discovers services и включает notifications.
5. Успешный descriptor write вызывает `onCaptureReady()`.
6. Запись становится `recording`, packet processor сбрасывается и начинает
   писать файлы.
7. Disconnect завершает запись и закрывает writers.

`onPause` всегда завершает текущую запись как `interrupted` и disconnect BLE,
кроме узкого случая активного запроса camera permission.

Фактические точки:

- `MainActivity.kt:534-560` — подготовка записи и connect;
- `BleSessionManager.kt:370-403` — notifications ready;
- `DeviceFeatureController.kt:366-385` — capture ready;
- `DeviceFeatureController.kt:131-143` — disconnect/finalize;
- `DeviceFeatureController.kt:227-231` — pause.

### 2.6. Камера

Сейчас камера:

- использует Camera2 + `MediaRecorder`;
- выбирает заднюю камеру, до 1920×1080, около 30 fps, H.264/MP4;
- не пишет звук;
- стартует только вручную после BLE capture ready;
- имеет отдельную кнопку Start/Stop video;
- останавливается также при общем disconnect/finalize;
- сохраняет `video.mp4` и `sync.json`.

Критические расхождения с целевым UX:

1. Видеопоток не отображается. Capture session содержит только
   `MediaRecorder.surface`; preview surface отсутствует.
2. Запуск датчика и видео — две разные операции.
3. Можно завершить видео и продолжить датчик, то есть одна сессия имеет
   неоднозначный фактический интервал.
4. Video card находится в длинном Overview и появляется только после начала
   live session; это не полноценный экран съёмки.
5. Первый Camera2 callback назначается после `setRepeatingRequest`, а
   `MediaRecorder.start()` вызывается следом. Callback может описывать camera
   frame до фактического первого закодированного MP4 frame.
6. Нет проверки MP4 через extractor/ffprobe после stop; валидность определяется
   только отсутствием исключения, наличием файла и ненулевым размером.
7. Нет явного startup timeout, общего барьера «первый sensor packet + первый
   video frame» и реакции на частичный старт.

Фактические точки:

- `AndroidVideoRecorder.kt:133-187` — единственный recorder surface;
- `AndroidVideoRecorder.kt:152-173` — repeating request до recorder start;
- `ProfilesUi.kt:157-219` — отдельная кнопка видео;
- `DeviceFeatureController.kt:698-773` — отдельный start/stop.

### 2.7. Текущая синхронизация

Сейчас sensor anchor создаётся в момент успешного включения BLE notifications,
а не в момент первого принятого/валидного sensor packet. Он содержит пару:

```text
wallClockEpochMillis
android.elapsedRealtimeNanos
```

Для камеры берётся Camera2 timestamp:

- `REALTIME` считается сравнимым с `elapsedRealtimeNanos`;
- иначе используется callback-time estimate.

`sync.json` хранит `offsetFromSensorNs` между capture-ready anchor и первым
camera callback.

Что это доказывает:

- события приложения можно расположить на одной Android monotonic timeline;
- абсолютное UTC можно оценить через сохранённую пару wall/monotonic;
- качество явно маркируется.

Чего это не доказывает:

- физический момент измерения сенсора;
- latency BLE от сенсора до Android callback;
- точный первый закодированный кадр MP4;
- drift между clock датчика и clock телефона;
- end-to-end допустимое смещение на реальном железе.

Дополнительная проблема: `raw_fragments.binlog` сохраняет wall-clock epoch
milliseconds, но не monotonic nanoseconds. `packets.bin` содержит device timer,
но не host receive timestamp. Поэтому текущие файлы не дают полного
воспроизводимого отображения каждого sensor packet на camera timeline.

Фактические точки:

- `DeviceFeatureController.kt:819-836` — clock anchor;
- `AndroidVideoRecorder.kt:189-220` — first-frame callback;
- `BleRawFragmentFileStore.kt:19-32` — только epoch millis;
- `PacketValidator.kt:3-8,93-99` — device timer;
- `BleSessionCsvExporter.kt:73-80` — оценочное абсолютное время.

### 2.8. Экспорт и Google Drive

Текущий Drive flow:

- создаёт ZIP с manifest и доступными артефактами;
- использует WorkManager, network constraints и exponential backoff;
- поддерживает resumable Google Drive upload;
- хранит очередь ZIP-файлов и quarantine для permanent failures;
- удаляет ZIP после успешной выгрузки.

Это полезный код для переиспользования концепций очереди, статусов, retry и
resumable HTTP. Но Drive не является серверной БД:

- анкеты не становятся нормальными серверными сущностями;
- сервер не может искать собак/сессии и проверять отношения;
- нет server receipt по каждому артефакту;
- нет восстановления списка профилей после reinstall;
- ZIP дублирует данные и задерживает отправку больших видео;
- лимит очереди 25 ZIP может остановить дальнейшее резервирование.

Итоговая архитектура должна удалить Drive-specific authorization/UI/worker
после успешного cutover, но сохранить ручной Share.

### 2.9. Android backup

Manifest включает `android:allowBackup="true"`, а backup/data extraction rules
остались шаблонными и не исключают БД, видео и токены. До production server
нужно явно:

- исключить server token/keystore metadata из backup;
- решить, должны ли большие локальные recordings попадать в cloud backup
  (рекомендация: нет, сервер уже является источником восстановления);
- протестировать device transfer отдельно от server restore.

## 3. Текущие автоматические проверки

В repository имеются:

- JVM protocol, BLE state, packet processor, storage/export и Drive tests;
- SQLite instrumentation round-trip;
- fake-driven BLE capture smoke;
- Compose navigation/export/fullscreen tests;
- проверки top safe inset, bottom inset и ширины chart controls.

Непокрытые целевые риски:

- required/conditional validation обеих анкет;
- сохранение/восстановление каждого questionnaire field;
- activity/location/surface dependency matrix;
- UI анкет при русском тексте, маленьком экране и large font;
- camera preview;
- единая Start/Stop state machine;
- реальный MP4;
- первый sensor packet и BLE latency;
- server API, PostgreSQL и server filesystem;
- resume после process death/network loss;
- download/restore и checksum;
- migration существующих неполных анкет.

## 4. Фактический прогон сборок и тестов

На базовом снимке был запущен:

```bash
ANDROID_HOME="$HOME/Android/Sdk" \
ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
./android/gradlew -p android testDebugUnitTest lintDebug assembleDebug \
  woonaApi31DebugAndroidTest --no-daemon --console=plain
```

Результат:

- JVM tests: прошли;
- debug APK: собран;
- lint: 0 errors, 45 warnings;
- managed-device instrumentation: 23 tests, 17 прошли, 6 упали.

Шесть падений:

1. `overview_isDefaultDestination`: тест ожидал один текст `Overview`, но
   одновременно видит заголовок и bottom-navigation label.
2. `driveConnect_isDisabledWhileAuthorizationIsInProgress`: тест искал
   `Connect`, фактический label отличается.
3. `requiredDogProfile_cannotBeSkipped`: assertion enabled-state расходится с
   фактическим required first-run flow.
4. Три `DeviceFeatureSmokeTest` падают при обращении video recorder к
   `context.display` через application context на API 31 managed device.

HTML report этого прогона:

```text
android/app/build/reports/androidTests/managedDevice/debug/woonaApi31/index.html
```

После прогона другой агент начал менять тесты и video recorder. Поэтому это
честный результат исследованного снимка, а не утверждение о более позднем
незакоммиченном состоянии.

Конкурентный агент позже перезаписал этот HTML report: на 20:33 текущий файл
показывает 23/23 tests passed. Это полезный сигнал, что перечисленные шесть
падений были устранены в меняющемся worktree, но не меняет привязку
функционального аудита к базовому commit.

## 5. Фактическая визуальная проверка

Проверка выполнена на отдельном Pixel 2/API 31 emulator, 1080×1920, density
420. Подключённое физическое устройство не использовалось.

Вручную открыты:

- first-run dog questionnaire, верх и низ;
- Overview с созданной собакой;
- Charts;
- Settings, верх и низ;
- session questionnaire, верх и низ;
- те же ключевые экраны при font scale 1.5;
- русский интерфейс при font scale 1.5.

На English/font 1.0 явных наложений на просмотренных состояниях нет. Длинные
анкеты прокручиваются, заголовок/Save остаются доступны. При font 1.5
English Overview также прокручивается до скрытых ниже элементов.

Подтверждённые visual defects на Russian/font 1.5:

1. В Settings строка `Не подключено` получает слишком узкую колонку рядом с
   длинной кнопкой и переносится почти по слогам/буквам.
2. Нижний пункт `Настройки` переносится на две строки.
3. В dog questionnaire заголовок `Карточка собаки` переносится на три строки и
   чрезмерно увеличивает header.
4. В session questionnaire header с `Карточка сессии`, `Отмена` и
   `Сохранить` не помещается: заголовок и actions визуально обрезаны верхней
   границей.
5. Технический UUID в поле номера сессии обрезается по ширине без полезного
   пользователю смысла.

Это не исправлялось: в план добавлены adaptive top actions, responsive
Settings rows, semantic bounds tests и полная RU/EN/font/device матрица.

Не были и не могли быть визуально доказаны на эмуляторе:

- camera preview, потому что текущий код его не имеет;
- реальный recording video state;
- BLE device states на настоящем датчике;
- качество/ориентация MP4;
- физическая синхронизация.

Их нельзя закрывать screenshot-тестом; физическая матрица находится в
`verification-plan.md`.

## 6. Вывод

Текущая база не требует переписывания BLE/protocol/storage. Надёжные части —
UUID, относительные пути, локальная финализация, WorkManager, snapshots и
артефактный каталог — нужно расширить.

Корневые изменения нужны в четырёх местах:

1. заменить nullable формы централизованным валидируемым контрактом;
2. заменить отдельную video-кнопку общей capture state machine с preview;
3. добавить host monotonic timestamps на фактическом sensor packet path;
4. заменить Drive ZIP backup на idempotent server sync отдельных сущностей и
   файлов.
