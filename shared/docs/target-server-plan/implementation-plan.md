# Подробный план реализации

## Общий порядок

Работы выполняются вертикальными безопасными шагами. Каждый шаг оставляет
приложение собираемым и сохраняющим локальные данные. Сервер не подключается к
live capture path, пока локальная запись не закончена.

Нельзя параллельно менять форму, SQLite migration, camera lifecycle и upload
worker одним большим commit: при потере данных будет невозможно определить
корень.

## Этап 0. Зафиксировать acceptance contract

### Действия

1. Утвердить target JSON Schemas из `models/`.
2. Утвердить перечень activity/group/surface.
3. Утвердить диапазоны чисел.
4. Утвердить, что `unknown/not_measured` считаются ответом.
5. Утвердить video default `true`, sensor-only только явным выбором.
6. Утвердить server artifact set.
7. Утвердить local retention первой версии: не удалять автоматически.
8. Выбрать baseline физического sync threshold после первой измерительной
   сессии; до измерения не писать фиктивные ±миллисекунды.
9. Зафиксировать single/multi-device ожидание. Схема уже не допускает silent
   conflicts, поэтому оба режима безопасны.
10. Сохранить anonymized fixture текущей SQLite v2 с:
    - несколькими собаками;
    - edited profile;
    - live/replay;
    - completed/interrupted;
    - всеми artifact types;
    - неполными legacy questionnaires.

### Готово, если

- JSON Schemas проходят validator;
- product owner понимает явные unknown;
- migration fixture не содержит персональных/реальных секретов;
- команда не меняет field semantics во время backend implementation.

## Этап 1. Централизовать анкеты без сервера

### Android изменения

1. Вынести typed enums и draft/value модели из большого UI-файла в `profile/`.
2. Реализовать два pure Kotlin validators:
   - `validateDogQuestionnaire`;
   - `validateSessionQuestionnaire`.
3. Validation result:
   ```text
   isValid
   errors: Map<FieldId, ErrorCode>
   firstInvalidField
   answeredCount
   totalRequiredCount
   ```
4. Реализовать canonical JSON serializer:
   - стабильный порядок keys;
   - schemaVersion;
   - trim;
   - JSON number;
   - explicit null только для disabled conditional fields.
5. Заменить `MutableMap<String,String>` на typed draft state.
6. Single-choice radio больше не toggle-off.
7. Реализовать dependency reducers:
   - breed -> breed details;
   - status -> numeric/detail;
   - observed none/unknown;
   - diagnoses -> details;
   - activity group -> activity;
   - location -> surface;
   - other -> details;
   - measurement status -> value/time.
8. Replay skip удалить.
9. UUID не показывать как номер; добавить session label.
10. Убрать ручную техническую дату начала из questionnaire. Фактический start
    назначается capture state machine.
11. Переводы хранить в resources, не в hardcoded `tr(...)`, чтобы UI tests могли
    стабильно находить labels и accessibility descriptions.

### Совместимость

Старые nullable JSON продолжают читаться legacy adapter. Новый serializer пишет
только schema v1 complete.

### Тесты

- pure validators;
- JSON round-trip;
- conditional cleanup;
- boundary numbers;
- locale decimal input;
- Compose dog/session happy path;
- first invalid scroll;
- replay без skip.

### Готово, если

- новую анкету невозможно сохранить пустой;
- invalid number не становится null;
- все dependency cases тестируются;
- существующая SQLite v2 открывается;
- live/replay всё ещё записываются локально.

## Этап 2. Локальная SQLite final schema и безопасная миграция

### Schema

Добавить:

- `dogs`;
- `dog_profile_versions`;
- расширенные `recordings`;
- `recording_sync`;
- расширенные `artifacts`;
- `server_sync_state`.

Не добавлять Room только ради этой миграции: текущий `SQLiteOpenHelper`
достаточен, а смена persistence library одновременно с моделью увеличит риск.

### Migration v2 -> v3

В одной SQLite transaction:

1. создать новые таблицы с `_new`;
2. проверить foreign keys включены;
3. для каждого `dog_profiles`:
   - сохранить текущий profile id как dog id;
   - создать profile version id;
   - сохранить исходный questionnaire JSON;
   - преобразовать однозначные поля;
   - complete только если новый validator подтверждает;
   - иначе `legacy_incomplete`;
4. для каждого recording:
   - сохранить UUID и относительную директорию;
   - связать с созданной profile version;
   - перенести status/source/timestamps/timezone;
   - сохранить old questionnaire;
   - отметить legacy при неполноте;
5. для каждого artifact:
   - сохранить id/type/path/size;
   - `sha256=NULL`, `upload_state=hash_pending`;
6. проверить counts old/new;
7. выполнить `PRAGMA foreign_key_check`;
8. только после проверок swap tables;
9. повысить `user_version`;
10. не удалять artifact files.

Hash больших файлов не вычислять в DB migration на main thread. После успешного
открытия worker заполняет hashes streaming.

### Crash/retry

- transaction rollback оставляет v2;
- migration повторяема;
- startup показывает понятную blocking ошибку при невозможности migrate;
- до исправления приложение не создаёт новую запись в частично мигрированной
  схеме.

### Тесты

- создать настоящую v2 DB fixture;
- upgrade;
- проверить row counts/relations/JSON/artifact paths/status;
- crash injection на середине copy;
- повторное открытие;
- empty DB;
- max-size JSON;
- missing artifact file;
- foreign key check.

### Готово, если

- fixture мигрирует без потери byte/file;
- исторические неполные записи читаются;
- новая запись использует immutable profile version;
- edit собаки не меняет старую recording reference.

## Этап 3. Единая capture state machine

### Действия

1. Создать один `RecordingSessionController` в `feature/device/` или расширить
   текущий controller без interface с одной реализацией.
2. Он владеет:
   - recording state;
   - packet capture;
   - BLE arm/start/stop;
   - video arm/start/stop;
   - finalization;
   - DB terminal transaction;
   - enqueue sync.
3. UI получает один immutable `RecordingUiState`.
4. `MainActivity` только вызывает intents и permissions; новая orchestration не
   добавляется в Activity.
5. Все terminal paths проходят через одну идемпотентную `finalizeOnce(reason)`.
6. Удалить separate video action из profile card.
7. Disable navigation/profile changes во время STARTING/RECORDING/FINALIZING
   либо показывать подтверждение, ведущее к единой Stop.
8. onPause/background:
   - если recording — finalize interrupted;
   - если только preview — release camera;
   - если permission dialog — не путать с background.

### Тесты

- double Start;
- double Stop;
- Stop во время STARTING;
- BLE failure до/после video;
- camera failure до/после sensor;
- pause;
- process recovery;
- writer timeout;
- exactly one terminal DB update;
- exactly one WorkManager enqueue.

### Готово, если

- пользователь видит одну Start и одну Stop;
- отдельное видео невозможно;
- все ресурсы имеют один owner;
- частичный failure сохраняет доступные данные.

## Этап 4. Monotonic sensor timeline v2

### Действия

1. Timestamp fragment в GATT callback.
2. Передавать `BleFragment`, а не голый ByteArray.
3. Обновить queue/assembler так, чтобы timestamps не терялись.
4. Writer создаёт `BLERAW2`.
5. Reader/replay понимает v1/v2.
6. Добавить `packet_timeline.bin` sidecar.
7. Sensor anchor = first accepted packet.
8. CSV использует device timer mapping + monotonic/UTC anchor, а не ручное
   session time.
9. CSV добавляет явные колонки:
   ```text
   estimated_utc
   host_monotonic_ns
   device_timer_ms
   sample_timer_ms
   ```
10. Sync JSON schema повысить и сохранить clock quality.

### Тесты

- v1 replay compatibility;
- v2 serialize/parse;
- fragment timestamps проходят queue;
- assembled packet получает правильный диапазон;
- timer wrap/reset;
- deterministic CSV UTC mapping;
- no wall-clock jump affects offsets.

### Готово, если

- по files можно восстановить mapping каждого accepted packet;
- current packet dump не сломан;
- старые replay работают.

## Этап 5. Preview и camera timestamps

### Действия

1. Добавить SurfaceView через `AndroidView`.
2. Разделить camera states:
   ```text
   closed
   opening
   preview
   starting_recording
   recording
   stopping
   error
   ```
3. Preview существует до Start.
4. Reconfigure capture session preview -> preview+recorder.
5. First-frame callback учитывать только после recorder start flag.
6. После stop проверить video track/duration/PTS через `MediaExtractor`.
7. Записать typed sync row + sync.json.
8. Экран recording удерживает orientation policy или корректно пересоздаёт
   preview без потери recording; выбранное поведение фиксируется тестом.
9. Камера и recorder освобождаются во всех lifecycle paths.
10. Добавить startup barrier/timeout.

### Тесты

Эмулятор/fake:

- preview placeholder/state;
- permission deny;
- no camera;
- state transitions;
- size/rotation selection pure logic;
- startup timeout;
- fake MP4 validation.

Физический device:

- preview не чёрный;
- MP4 открывается;
- orientation;
- 1/10/30 minute capture;
- screen off/background behavior;
- storage nearly full;
- camera disconnect/error.

### Готово, если

- оператор видит реальный поток до и во время записи;
- красный indicator соответствует MediaRecorder state;
- Stop одной кнопкой закрывает оба потока;
- MP4 подтверждён, не только ненулевой.

## Этап 6. Backend и Docker skeleton

### Структура

```text
shared/server/
  app/
  migrations/
  tests/
  Dockerfile
  pyproject.toml
shared/compose.yaml
shared/.env.example
var/                 # ignored
```

### Действия

1. Поднять PostgreSQL 18.
2. Создать API health live/ready.
3. Подключить migrations.
4. Применить target DDL по последовательным migration.
5. Настроить storage root + incoming.
6. Добавить debug device provisioning command.
7. Добавить bearer auth.
8. Ограничить body/chunk/file sizes.
9. Добавить structured logs request id.
10. `.env`, tokens, `var/`, DB dumps исключить из git.

### Проверки

- `docker compose config`;
- build;
- fresh up;
- health;
- migrations с пустой DB;
- upgrade с предыдущей migration;
- API не ready при DB/storage failure;
- volumes переживают restart/down/up;
- token revoke.

### Готово, если

- локальный stack поднимается одной документированной командой;
- DB/FS persistent;
- API не стартует «зелёным» без dependencies.

## Этап 7. Metadata API

### Действия

1. Реализовать отдельный `PUT dog/profile version`.
2. Реализовать `PUT recording manifest`.
3. Server-side questionnaire validation.
4. Immutable profile version logic.
5. Dog revision conflict.
6. Artifact manifest creation.
7. Recording finalize preconditions.
8. Read endpoints для dogs/history.
9. Error envelope:
   ```json
   {
     "requestId": "...",
     "code": "questionnaire_invalid",
     "message": "...",
     "fieldErrors": {}
   }
   ```
10. Не возвращать stack trace клиенту.

### Tests

- actual PostgreSQL, не SQLite substitute;
- профиль sync без единой recording;
- несколько offline profile versions по порядку;
- all constraints;
- idempotency;
- concurrent profile edits;
- legacy migration authorization;
- pagination;
- malformed JSON/extra fields;
- timezone/RFC3339.

### Готово, если

- metadata round-trip;
- duplicate request безопасен;
- invalid data не попадает в DB.

## Этап 8. Resumable artifact API

### Действия

1. HEAD offset.
2. PATCH append с exact offset.
3. per-artifact lock.
4. `.part`.
5. size limit.
6. complete hash + atomic rename.
7. recording finalize receipt.
8. Range download.
9. stale `.part` cleanup только для давно неактивных и не referenced uploads.
10. integrity scan command.

### Failure injection

- kill API mid-chunk;
- kill после file write до DB offset;
- kill после DB update до response;
- disk full;
- permission denied;
- hash mismatch;
- concurrent PATCH;
- path traversal filename;
- DB restart.

Для write-before-offset crash server при следующем request сверяет реальный
`.part` size с DB и безопасно reconciles к меньшему доказанному/фактическому
значению по выбранному алгоритму. Это поведение должно иметь отдельный test.

### Готово, если

- 1+ GiB synthetic file проходит с restart;
- hash исходного/download совпадает;
- orphan/corrupt видимы, не маскируются complete.

## Этап 9. Android server client

### Действия

1. Добавить server settings и Keystore token storage.
2. Реализовать тонкий HTTP client на существующем `HttpURLConnection` pattern.
3. JSON DTO привязать к schema version.
4. Добавить profile sync work при каждом сохранении новой версии.
5. Добавить unique `ServerSyncWorker` на recording.
6. Recording worker сначала подтверждает profile version.
7. Streaming hash/read без загрузки файла в память.
8. Chunk resume.
9. Network/Wi-Fi constraints.
10. Long-running foreground notification для большого видео.
11. Persist progress после каждого confirmed chunk.
12. Показать user-visible sync state.
13. 401/422/hash конфликт не retry бесконечно.
14. Capture никогда не ждёт worker.

Не добавлять generic repository/gateway hierarchy, пока один server client и
один worker решают задачу.

### Tests

- stdlib fake HTTP server для JVM client cases;
- instrumentation с Docker API;
- profile create/edit без recording;
- process/worker cancellation;
- constraints;
- 401/409/422/429/500;
- resume;
- concurrent recordings;
- no memory spike large file.

### Готово, если

- завершённая offline session автоматически доходит после сети;
- UI не врёт о server success до receipt;
- repeated worker не дублирует данные.

## Этап 10. Download и restore

### Действия

1. Provision fresh app.
2. Fetch dogs/current versions.
3. Merge в SQLite с revision.
4. Fetch recent metadata.
5. Mark artifacts remote-only.
6. On-demand Range download.
7. Verify hash и atomic rename.
8. Не заменять локальный divergent file без явной ошибки/conflict.
9. Export умеет включить remote artifact после download.

### Tests

- reinstall emulator;
- empty local DB restore;
- interrupted download;
- insufficient storage;
- server corrupt/missing file;
- same artifact already local;
- metadata pagination;
- Russian/English remote-only UI.

### Готово, если

- профили и история восстанавливаются;
- скачанный каждый тип artifact совпадает byte-for-byte.

## Этап 11. Migration существующих данных на сервер

### Действия

1. После local v3 migration поставить все terminal recordings в pending.
2. Hash существующих files.
3. Upload complete и legacy metadata.
4. Server migration path принимает `legacy_incomplete`, но только для
   provisioned migration device/command.
5. Сохранить old raw questionnaire JSON.
6. Сформировать migration report:
   - profiles total/complete/legacy;
   - recordings by status;
   - artifacts count/bytes;
   - missing files;
   - upload receipts;
   - failures.
7. На UI old incomplete profile требует заполнения до новой сессии.

### Reconciliation

Сверить:

```text
local dogs == server dogs
local recordings == server recordings
для каждого local existing artifact:
  server status available
  local size == server size
  local sha256 == server sha256
```

### Готово, если

- все существующие данные либо verified server-side, либо перечислены в
  конечном failure report с причиной;
- нет silent skip.

## Этап 12. Visual redesign integration

### Действия

1. Отдельный preflight/recording state внутри Overview, без новой top-level tab.
2. Camera preview не помещать внутрь карточки списка профилей.
3. Recent recordings показывают capture и sync status отдельно.
4. Settings Drive card заменить Server Sync.
5. Export sheet удалить Drive action.
6. Loading/progress не накрывает системные bars.
7. Keyboard/Ime padding у анкет.
8. LazyColumn item sections, stable keys, restore scroll.
9. Error banner не закрывает Start/Stop.
10. Stop всегда в safe bottom area и минимум 48 dp.

Полная матрица — `verification-plan.md`.

### Готово, если

- screenshot/semantic bounds checks проходят;
- ручная проверка всех состояний на small phone/large font;
- нет наложений RU/EN/light/dark/orientation.

## Этап 13. Cutover с Google Drive

### Сначала

- server sync включён у пилотных устройств;
- минимум несколько дней/оговорённое число реальных sessions без потерь;
- restore drill;
- backup/restore сервера;
- monitoring;
- operator runbook.

### Затем

1. выключить Drive auto-upload по умолчанию;
2. один release оставить fallback, если продукт требует;
3. удалить Drive UI/auth/upload/archive queue;
4. удалить Google auth dependency, если больше не используется;
5. удалить stale strings/tests/files;
6. оставить ZIP exporter только для manual Share;
7. проверить APK size и permissions.

### Готово, если

- ни один production path не зависит от Google Drive;
- все данные доступны через server;
- manual export работает.

## Этап 14. Production readiness

### Server

- TLS;
- DB role с минимальными правами;
- storage ownership/permissions;
- secrets вне image/repo;
- backup;
- restore drill;
- disk alerts;
- DB connection limits;
- request/chunk limits;
- log rotation;
- stale partial cleanup;
- integrity schedule;
- migration runbook;
- rollback application version без downgrade data loss.

### Android

- release build/signing;
- production endpoint;
- debug cleartext отсутствует в release;
- backup rules исключают token/large recordings;
- privacy/consent для video;
- camera indicator/accessibility;
- WorkManager notification;
- upgrade from installed previous APK;
- low disk behavior.

### Финальный критерий

Одна физическая сессия должна пройти:

```text
complete questionnaire
-> camera preview + BLE ready
-> one Start
-> visible recording
-> one Stop
-> local verified files
-> automatic upload
-> server DB relations
-> server filesystem hashes
-> fresh-device metadata restore
-> artifact download with same SHA-256
```

Без этого deploy не называется завершённым.
