# Итоговый отчёт о реализации и проверке

Дата прогона: 2026-08-01  
Рабочая ревизия: `8417c190d6e425bb7b69061c65f47fbeba3d2f16` с текущими
незакоммиченными изменениями реализации  
Android: `versionName=1.2-video-sync`, `versionCode=3`, min API 31,
target API 36  
Локальный backend: FastAPI, PostgreSQL 18, файловое хранилище Docker Compose

## 1. Итог

Программная часть задачи реализована и прошла локальную приёмку:

- анкеты собаки и сессии обязательны, имеют явные значения
  `unknown`/`not_measured` и очищают несовместимые дочерние ответы;
- запись BLE и выбранного видео управляется одним Start/Stop, показывает
  preview/состояние записи и сохраняет общую монотонную шкалу времени;
- структурированные данные выгружаются через API в PostgreSQL, бинарные
  артефакты — в файловую систему сервера;
- локальная SQLite остаётся offline-first источником и очередью до серверного
  подтверждения;
- upload возобновляется с подтверждённого offset, download — с Range и
  последующей проверкой SHA-256;
- restore возвращает профили, историю версий, записи, sync metadata и
  артефакты;
- Google Drive удалён из итогового кода, зависимостей, permissions и workers;
- документация содержит только итоговые модели PostgreSQL, SQLite, JSON Schema,
  описание полей и готовые SVG-визуализации.

Статус программной приёмки: **PASS**.  
Статус приёмки на Pixel 10 Pro Fold/API 37: **PASS с реальной камерой и
mock-BLE**. Сам целевой BLE-датчик по условию прогона не использовался.

## 2. Автоматизированные проверки Android

| Проверка | Результат | Что подтверждено |
|---|---:|---|
| `./android/tools/check.sh fast` | PASS | JVM tests, debug lint, debug APK и v2-подпись |
| `./android/tools/check.sh full` | PASS | 123/123 JVM tests; чистый Gradle Managed Pixel 2/API 31, 34/34 instrumentation tests |
| Pixel 10 Pro Fold, Android 17/API 37 | PASS | 34/34 connected tests, 0 skipped, 0 failed, 0 errors |
| `testDebugUnitTest` | PASS | 123/123, 0 skipped, 0 failed |
| `lintDebug` | PASS | 0 errors |
| `lintRelease` | PASS | 0 errors |
| `assembleDebug` | PASS | собран подписанный debug APK |
| `assembleRelease` | PASS | собран unsigned release APK |

Оставшиеся 48 lint-сообщений — рекомендации по обновлению зависимостей,
KTX/style и launcher assets; ошибок корректности или безопасности среди них
нет. Предупреждение о runtime locale устранено отключением language split для
App Bundle.

APK для закрытого полевого теста:
`android/dist/Woona-field-test-1.2-video-sync-debug.apk`, 61 543 031 byte,
Android Debug certificate, APK Signature Scheme v2, SHA-256
`1ba1ac3bfc6357f3e76623e6f618d053427730f00d9a71dcf75b5a381dc79010`.

Release APK:
`android/app/build/outputs/apk/release/app-release-unsigned.apk`, 44 033 483 byte.
Он намеренно не подписан: production keystore и пароли в репозитории не
хранятся. При переданных `ANDROID_SIGNING_*` переменных Gradle применит
production signing config.

## 3. Что покрыто Android-тестами

### Анкеты и UI

- пустую анкету нельзя сохранить;
- ошибки показываются у полей, первый invalid field прокручивается в viewport;
- radio-ответ нельзя случайно снять повторным нажатием;
- смена activity group очищает несовместимый activity type;
- location ограничивает допустимые surfaces;
- условные details/value обязательны только для активного parent-ответа;
- измерение требует значения и RFC 3339 timestamp, `not_measured` хранит
  `null`;
- draft анкеты и открытый session dialog переживают recreation Activity;
- bottom navigation, export sheet, server settings и capture state доступны
  при font scale 2.0;
- chart controls и chart viewport доступны в низком landscape.

### SQLite и восстановление

- profile, immutable profile version, recording, sync и artifacts проходят
  round-trip;
- запись сохраняет ссылку на ту версию профиля, с которой была начата;
- миграция v2 сохраняет UUID, запись и файл, помечает старые анкеты
  `legacy_incomplete`, затем streaming worker вычисляет SHA-256;
- миграция v4 -> v5 ремонтирует canonical profile hash для точного совпадения
  Kotlin/Python;
- `PRAGMA integrity_check=ok`, foreign keys включены;
- повторный restore metadata идемпотентен;
- remote-only артефакт не выдаётся за локально скачанный;
- локальный конфликт SHA не перезаписывается молча.

### Capture и синхронизация

- fake BLE проходит start/stop/replay/state-machine сценарии;
- batching-обёртка сохраняет wall-clock и `elapsedRealtimeNanos` входящего
  BLE-пакета, а не подменяет их другим Android clock;
- double start/stop не создаёт вторую активную запись;
- Stop на границе обработки входящего BLE-пакета не создаёт lock inversion,
  deadlock или ANR;
- packet/raw/timeline/CSV writers закрываются и регистрируются;
- camera failure переводит запись в явный degraded state без потери BLE;
- sync metadata содержит UTC, `elapsedRealtimeNanos`, первый sensor packet,
  camera timestamp source, first-frame callback/PTS и offset;
- экран записи показывает preview surface, состояние, offset и одну кнопку
  Stop.

### Физическая запись и HTTP round-trip из Android

Instrumentation test на физическом Pixel снял реальный MP4, подал
mock-BLE через production capture pipeline и обратился к Docker API через
временный `adb reverse`:

1. upload обязательного профиля;
2. upload recording manifest;
3. upload шести типов артефактов: packet, timeline, raw,
   diagnostic, video и sync;
4. server completion/receipt;
5. restore dogs/recordings;
6. сравнение manifest, имён, типов, size и SHA-256 на телефоне и
   сервере;
7. download реального MP4 и повторная проверка size/SHA-256.

Физические записи завершились без crash/ANR/CameraDevice error. Отдельный
10-минутный soak дал 601,873 с capture, непрерывный mock-BLE поток,
457 524 393 byte MP4 и 458 786 550 byte во всех шести артефактах. На
сервере: `status=completed`, `ingest=complete`, 6/6 `available`; download видео
и SHA-256 прошли. Sensor timeline покрыл 600,268 с, first-sensor/first-frame offset
составил 181 655 757 ns, first sample PTS — 0, quality — `realtime/arrival_aligned`.

## 4. Docker/PostgreSQL/API

Clean-room стенд:

```text
project: woonaacceptance
API:     127.0.0.1:18080
DB:      отдельный named volume
files:   test-run/clean-room-storage
```

На пустом clean-room выполнены 11/11 серверных тестов:

- все required dog/session поля и conditional зависимости;
- invalid payload -> 422;
- bearer auth;
- immutable profile и optimistic revision conflict;
- идемпотентный manifest;
- HEAD/PATCH resumable upload и offset mismatch;
- server-side size/SHA completion;
- повторный artifact/recording finalize;
- Range download;
- path traversal rejection;
- zero-byte artifact;
- явный legacy migration path;
- pagination dogs и recordings.

После теста состояние составило:

```text
dogs=2 | recordings=3 | artifacts=7
```

После рестарта API и PostgreSQL получено то же значение
`2 | 3 | 7`, `/health/ready=200`, `/v1/integrity` вернул:

```json
{"status":"ok","problems":[]}
```

## 5. Backup/restore, auth и отказоустойчивость

В отдельном restore drill проверены PostgreSQL custom dump и копия файловой
системы. После восстановления в новый volume и пустой storage:

- counts совпали: `2 dogs | 3 recordings | 7 artifacts`;
- `/v1/integrity` вернул `ok`;
- SHA сырого файла в PostgreSQL и восстановленной файловой системе совпал:
  `6e81b762f3eceaf8639333ab2d5ddd9e1bd296306781bec0c56a2389d65aed22`.

Auth drill:

- неверный token -> 401;
- второе устройство получает `/v1/me=200`;
- данные первого устройства для него недоступны -> 404;
- после revoke token второго устройства -> 401.

Readiness drill:

- недоступная/непригодная для записи storage -> 503
  `storage_unavailable`;
- остановленная PostgreSQL -> 503 `database_unavailable`;
- после восстановления обеих зависимостей -> 200 `ready`.

Compose конфигурации основного и acceptance-стендов валидны. PostgreSQL не
публикуется наружу, основной API привязан к `127.0.0.1`. Cleartext HTTP
разрешён только debug-варианту для эмулятора `10.0.2.2` и USB-loopback
`127.0.0.1`; release
manifest такого исключения не содержит. В production обязательны HTTPS/VPN,
уникальные device token, сильный пароль БД и production signing key.

## 6. Визуальная проверка

Вручную просмотрены 24 screenshot-состояния на Pixel 2/API 31:

- English и Russian;
- light и dark;
- portrait и landscape;
- font scale 1.0, 1.5 и 2.0;
- first-run dog form и required errors;
- Overview с профилем;
- пустые Charts;
- Settings, server section и token dialog;
- session form, required errors и activity dependency;
- capture/recording state с preview area, status, offset и Stop.

В ходе просмотра найдены и исправлены два реальных дефекта:

1. в landscape нижняя часть Charts была обрезана — низкий viewport теперь
   получает вертикальный scroll и ограниченную высоту графика;
2. изменение font scale пересоздавало Activity и закрывало незавершённую
   session form — состояние dialog и draft теперь сохраняется в saved state.

После исправлений повторные screenshots и semantic bounds/navigation tests
прошли. На проверенных состояниях блокирующих наложений, недоступного Stop,
перекрытия bottom navigation или обрезанных chart controls не осталось.
Дополнительно вручную просмотрены финальная установленная сборка на
внешнем дисплее Pixel 10 Pro Fold и capture-экран с активной записью:
перекрытий и обрезанных controls нет.

## 7. Что остаётся до боевой раздачи

Реальная камера, локальная запись, синхронизация, выгрузка, restore и download
на одном Pixel закрыты. До боевой раздачи остаются обязательными:

- реальный целевой BLE GATT, disconnect/reconnect и firmware-specific timer;
- отдельный 30-минутный thermal/storage soak;
- минимум 30 визуально сопоставимых sensor events на записи 10–20 минут;
- median/p95/max абсолютного offset и drift в начале/середине/конце;
- повтор ещё на одной модели телефона;
- камера при thermal/disconnect, revoke permission и почти заполненном
  физическом storage;
- production нагрузочный/capacity прогон на согласованных лимитах, включая
  100 MiB и 1+ GiB артефакты.

Кроме того, для раздачи людям вне локальной сети нужны реальный HTTPS-адрес
сервера/device token и production-signed APK/AAB. Текущий APK пригоден для
закрытого теста, но подписан Android Debug key.

## 8. Итоговые артефакты документации

- [поля и отношения](models/fields.md);
- [PostgreSQL DDL](models/postgresql.sql);
- [SQLite DDL](models/sqlite.sql);
- [dog questionnaire JSON Schema](models/dog-questionnaire.schema.json);
- [session questionnaire JSON Schema](models/session-questionnaire.schema.json);
- [серверная ER-модель](visualizations/data-model.svg);
- [локальная ER-модель](visualizations/local-data-model.svg);
- [контекст системы](visualizations/system-context.svg);
- [capture sequence](visualizations/capture-sequence.svg);
- [upload sequence](visualizations/upload-sequence.svg);
- [recording state](visualizations/recording-state.svg);
- [UI flow](visualizations/ui-flow.svg).

Обе DDL-модели проверены исполнением с остановкой на первой ошибке:
PostgreSQL-файл создал 6 таблиц в отдельной временной БД PostgreSQL 18,
SQLite-файл создал 6 таблиц, выставил `user_version=5`,
`foreign_key_check` не вернул нарушений, `integrity_check=ok`. Обе JSON Schema
успешно разобраны как JSON и дополнительно используются серверными
validation tests.

## 9. Дополнение: iOS parity, 2026-08-17

- Старый SwiftUI-проект очищен и импортирован в `ios/`; DerivedData, emulator
  data, nested Git и user-specific Xcode state не переносились.
- Добавлены обязательные dog/session анкеты, SQLite v5, immutable profile
  versions, recent history, AVFoundation preview/H.264, monotonic packet
  timeline, `sync.json`, Keychain, resumable upload, metadata restore и
  проверяемый Range download.
- Чистый Compose/PostgreSQL прогон: Alembic `0002 (head)`, 13/13 server/schema
  tests. Отдельный iOS device token читает общие Android данные, но не может
  изменять чужую незавершённую artifact upload.
- Повторный `./android/tools/check.sh fast`: PASS; JVM tests, lint, debug APK и
  APK Signature Scheme v2 не регрессировали.
- Xcode project/scheme metadata, 46 Swift source files и объявления 125 XCTest
  methods прошли доступную Linux static-проверку. Сами Swift compile/XCTest и
  simulator/physical-device сценарии не запускались: для них обязателен Mac с
  Xcode, а для BLE/camera/sync-quality — подписанный iPhone и целевой датчик.
