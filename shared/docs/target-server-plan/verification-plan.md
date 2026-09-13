# План полной проверки

## 1. Принцип приёмки

Проверка считается завершённой не после одного зелёного `assemble`, а после
последовательного доказательства четырёх независимых свойств:

1. приложение корректно собирается и валидирует ввод;
2. локальная запись не теряет данные при ошибках и перезапусках;
3. сервер подтверждает те же данные в PostgreSQL и файловой системе;
4. данные восстанавливаются на чистом устройстве и совпадают с оригиналом.

Эмулятор используется для воспроизводимых UI, SQLite и HTTP E2E. Реальный
телефон и датчик обязательны для камеры, BLE, качества MP4 и физической
синхронизации.

Каждый прогон сохраняет:

```text
test-run/
  versions.txt
  git-revision.txt
  gradle/
  docker/
  api-logs/
  db-snapshots/
  integrity-report.json
  screenshots/
  physical-sync/
  acceptance-report.md
```

В отчёте указываются commit, APK version, Android device/model/API, firmware
датчика, PostgreSQL/API version, начало и конец прогона в UTC.

## 2. Уровни обязательных проверок

### 2.1. На каждом небольшом Android изменении

```bash
./android/tools/check.sh fast
```

Проверяются:

- Kotlin/JVM tests;
- Android lint;
- debug compilation/package;
- подпись debug APK;
- отсутствие случайного release secret.

Изменение нельзя передавать дальше при новом lint error, compiler warning,
который означает потерю nullability/unchecked cast, или падении JVM test.

### 2.2. При изменении SQLite, Compose, preferences, BLE fake или lifecycle

```bash
./android/tools/check.sh full
```

Дополнительно запускается managed Pixel 2 API 31. Ветка не готова, пока:

- instrumentation suite зелёный;
- XML/HTML reports сохранены;
- тест не пропущен из-за отсутствия аппаратной камеры и не назван физической
  проверкой;
- flaky test повторён отдельно и исправлена причина, а не добавлен blind retry.

### 2.3. При изменении сервера или sync-контракта

Поднимается чистый Docker stack:

```bash
cd shared
docker compose config
docker compose build
docker compose up -d --wait
```

После прогона обычная остановка не удаляет volumes. `down -v` допустим только
для явно отмеченного destructive clean-room test.

## 3. Questionnaire test matrix

### 3.1. JSON Schema

Для обеих схем:

- schema сама проходит Draft 2020-12 validator;
- один полный valid fixture проходит;
- каждое required поле удаляется по одному и fixture отклоняется;
- extra property отклоняется;
- пустая строка отклоняется;
- граничные numeric значения принимаются;
- значение на один шаг за каждой границей отклоняется;
- integer не принимается там, где семантика требует enum;
- `null` принимается только у disabled conditional field;
- неизвестное enum-значение отклоняется.

### 3.2. Анкета собаки

Отдельные параметризованные проверки:

| Родитель | Значение | Обязательное дочернее | Должно быть `null` |
|---|---|---|---|
| breedStatus | purebred | breedName | resembles |
| breedStatus | mixed | breedName, resembles | — |
| breedStatus | unknown | — | breedName, resembles |
| ageStatus | known/estimated | years или months, non-unknown source | — |
| ageStatus | unknown | ageSource=unknown | years, months |
| weightStatus | measured/estimated | weightKg | — |
| weightStatus | unknown | — | weightKg |
| bodyConditionStatus | assessed | score 1–9 | — |
| bodyConditionStatus | unable | — | score |
| neckCircumferenceStatus | measured | circumference | — |
| neckCircumferenceStatus | not_measured | — | circumference |
| shavedAreasStatus | present | details | — |
| shavedAreasStatus | none/unknown | — | details |
| diagnosesStatus | yes | details | — |
| diagnosesStatus | no/unknown | — | details |
| housing | other | housingDetails | — |
| housing | любое другое | — | housingDetails |
| walksStatus | known | description | — |
| walksStatus | none/unknown | — | description |
| notesStatus | provided | notes | — |
| notesStatus | none | — | notes |

Для `observedSigns`:

- пустой список запрещён;
- `none` допустим один;
- `unknown` допустим один;
- `none`/`unknown` с симптомом запрещён;
- два обычных разных симптома допустимы;
- duplicate запрещён.

### 3.3. Анкета сессии

Activity matrix:

- каждая группа принимает только свой список activity;
- смена `locomotion -> stationary` очищает прежний `walk/trot/...`;
- `mixed` и `other` требуют details;
- обычная activity требует details=`null`;
- нет payload, эквивалентного старому `rest + gait + activity`.

Location matrix:

- indoor принимает только indoor surfaces;
- outdoor принимает только outdoor surfaces;
- concrete принимается в обоих;
- смена location очищает несовместимую surface;
- other требует surfaceDetails.

Measurements:

- у каждого измерения status обязателен;
- measured требует value;
- not_measured требует value=`null`;
- если хотя бы одно measured, требуется valid RFC 3339 `measurementAtUtc`;
- если все not_measured, timestamp=`null`;
- `preMeasurementState=other` требует details.

Video:

- `videoRequested` всегда присутствует;
- отсутствие boolean отклоняется;
- `true` требует camera preflight до Start;
- camera error не меняет значение на `false`;
- после Start значение immutable.

### 3.4. UI поведения

Для каждой анкеты:

- Save пустой формы невозможен;
- после попытки сохранения видны inline errors;
- первый invalid field попадает в viewport;
- клавиатура не закрывает текущее поле и Save/Next;
- back/cancel не теряет уже сохранённую сущность;
- rotation/process recreation сохраняет draft либо явно подтверждает discard;
- radio повторным нажатием остаётся выбранным;
- parent change очищает только ставшие недопустимыми child values;
- progress `N из M` пересчитывается с учётом enabled полей;
- serialized payload совпадает с данными на экране;
- сервер повторно отклоняет подменённый invalid payload.

## 4. SQLite и migration

### 4.1. Fixtures

Нужны отдельные реальные SQLite v2 fixtures:

- пустая БД;
- один профиль с минимальным текущим JSON;
- максимально заполненный профиль;
- live/replay completed;
- interrupted и оставшаяся `preparing`;
- запись со всеми типами файлов;
- artifact row без файла;
- файл без artifact row;
- invalid legacy number;
- unicode/Russian/emoji;
- несколько изменений одного профиля.

### 4.2. Upgrade assertions

После v2 -> final schema:

- количество dogs/profile versions/recordings/artifacts объяснимо и совпадает с
  migration report;
- все UUID и relative directories сохранены;
- old questionnaire JSON доступен для audit;
- complete/legacy status выставлен validator, а не наличием JSON;
- recording ссылается на конкретную profile version;
- edit создаёт новую version и не меняет старую recording;
- `PRAGMA foreign_key_check` пуст;
- user version повышен только после успешной transaction;
- artifact bytes не изменились;
- hash считается streaming после DB migration;
- повторное открытие не создаёт duplicates.

### 4.3. Failure tests

- process kill в каждой migration phase;
- SQLITE_FULL;
- read-only files directory;
- malformed JSON;
- missing file;
- insufficient free storage;
- reopen после rollback;
- concurrent worker не стартует до завершения migration.

## 5. Capture state machine

Pure/fake tests проходят все переходы:

```text
IDLE -> PREFLIGHT -> READY -> STARTING -> RECORDING
RECORDING -> FINALIZING -> COMPLETED
любое активное состояние -> FINALIZING -> INTERRUPTED/FAILED
```

Проверки:

- double Start создаёт одну recording;
- double Stop выполняет finalization один раз;
- Stop во время STARTING не оставляет открытый recorder/GATT/writer;
- permission denied возвращает в preflight;
- camera ready, BLE fail;
- BLE ready, camera fail;
- первый sensor packet timeout;
- первый video frame timeout;
- disconnect;
- app background;
- activity recreation;
- low storage;
- packet writer exception;
- MediaRecorder stop exception;
- WorkManager enqueue ровно один раз;
- все terminal paths имеют `endedAtUtc`, reason и доступные artifacts.

## 6. Camera and BLE verification

### 6.1. Эмулятор/fake

Эмулятор доказывает только:

- разрешение camera permission;
- preview placeholder/error states;
- layout preview и recording indicator;
- state machine;
- size/rotation selection pure functions;
- единый Start/Stop UI;
- корректное сообщение при отсутствии camera.

### 6.2. Реальный Android phone

Для каждого поддерживаемого телефона:

- rear preview виден до Start;
- preview не заморожен во время записи;
- индикатор recording появляется только после recorder started;
- видео H.264/MP4 открывается штатным player и `MediaExtractor`;
- video track, duration, first/last PTS валидны;
- orientation правильная portrait/landscape;
- тесты 10 секунд, 1, 10 и 30 минут;
- Stop закрывает BLE и video одной кнопкой;
- экран выключен/background обрабатывается по принятому контракту;
- storage nearly full даёт terminal error без потери sensor files;
- camera disconnect/thermal error не теряет metadata;
- permission revoke после preflight обрабатывается;
- приложение можно снова открыть и начать следующую сессию.

### 6.3. Physical synchronization

Для 10–20-минутной записи сделать минимум 30 видимых sensor events:

- 10 в начале;
- 10 в середине;
- 10 в конце.

Сохранить:

- исходное видео;
- raw fragments с host monotonic timestamps;
- packet timeline/device timer;
- скрипт/таблицу выбранных frame PTS и sensor peaks;
- offset каждого события;
- median, p95, max absolute offset;
- drift start-to-end;
- camera timestamp source;
- calibration offset.

Повторить минимум на двух телефонах. Acceptance threshold утверждается по
продуктовой задаче и baseline; до этого нельзя маркировать BLE arrival alignment
как точную hardware synchronization.

## 7. Docker backend verification

### 7.1. Startup/readiness

- fresh volumes;
- existing volumes;
- DB unavailable;
- storage unavailable/read-only;
- migration current;
- migration outdated;
- health live отдельно от ready;
- API ready только после DB query и storage write/read probe;
- `depends_on: service_healthy`/healthcheck не маскирует падение после старта.

### 7.2. PostgreSQL constraints

На настоящем PostgreSQL, не SQLite substitute:

- UUID/FK/unique/partial indexes;
- одна current profile version;
- recording/profile composite relation;
- timestamps and terminal interval;
- JSON object requirement;
- allowlisted statuses/types;
- artifact size/available invariants;
- path checks;
- transaction rollback;
- optimistic dog revision;
- concurrent updates;
- pagination index plans для representative объёма.

### 7.3. Filesystem

- filename не управляет server path;
- slash, backslash, `..`, absolute path и unexpected extension отклоняются;
- `.part` и final находятся на одной filesystem;
- final rename atomic;
- available file immutable;
- permission denied/disk full видимы;
- stale `.part` cleanup не удаляет active upload;
- orphan scan перечисляет DB-without-file и file-without-DB;
- integrity scan сверяет size/hash.

## 8. Upload verification

### 8.1. Manifest

- профиль, сохранённый без recording, автоматически появляется на сервере;
- несколько offline edits одного dog выгружаются в определённом порядке;
- profile revision conflict не уничтожает локальную версию;
- complete dog/session manifest проходит;
- invalid questionnaire -> 422 и field errors;
- тот же UUID/content идемпотентен;
- тот же UUID/другой content -> 409;
- wrong device/revoked token -> 401/403;
- stack trace/body не попадает в response/log;
- legacy incomplete доступен только migration path.

### 8.2. Chunks

Для каждого artifact type и synthetic файлов 0 B, 1 B, chunk-1,
chunk, chunk+1, 100 MiB и 1+ GiB:

- первый offset 0;
- каждый PATCH подтверждает точный offset;
- lost response не удваивает chunk;
- client kill продолжает по HEAD;
- API kill после write;
- API kill после DB update до response;
- PostgreSQL restart;
- offset mismatch;
- concurrent PATCH;
- hash mismatch;
- oversize;
- 429/500 retry;
- 401/422 permanent;
- cancellation между chunks.

### 8.3. Server receipt

Recording нельзя пометить server complete, пока:

- manifest valid;
- обязательный artifact set присутствует;
- каждый файл `available`;
- размер совпадает;
- server сам вычислил SHA-256;
- receipt сохранён в DB и Android.

Повторный finalize возвращает тот же результат.

## 9. Download and restore verification

Чистый эмулятор/телефон:

1. установить APK без local DB;
2. provision тот же device/account scope;
3. загрузить dogs/current versions;
4. загрузить history metadata с pagination;
5. убедиться, что artifacts показаны remote-only;
6. скачать каждый artifact type;
7. оборвать download на нескольких offsets;
8. продолжить через Range;
9. проверить local size/SHA-256;
10. открыть video, replay packet/raw и создать CSV;
11. повторить restore — duplicates нет.

Негативные случаи:

- недостаточно места;
- server file missing;
- corrupt response;
- ETag/hash changed;
- local divergent file;
- expired/revoked token;
- offline after metadata;
- app process kill.

Local divergent file не перезаписывается молча.

## 10. End-to-end reconciliation

После прогона приложение генерирует local manifest, сервер — independent
integrity report. Сравниваются:

```text
dogs count and ids
profile version count, ids and canonical JSON hashes
recording count, ids, statuses and timestamps
sync anchors/quality
artifact count/type/name
expected size == server size
local sha256 == server sha256
server receipt exists
```

Затем те же файлы скачиваются в новую папку и сравниваются byte-for-byte.

Pass допускается только при:

- нуле silent skip;
- нуле unexplained row/file;
- каждом failure с recording/artifact id и причиной;
- повторном E2E после restart API/PostgreSQL;
- сохранности volumes после `down`/`up`.

## 11. Visual verification matrix

### 11.1. Экраны и состояния

Проверить не только три tab, но каждое состояние:

- first-run required dog questionnaire;
- dog questionnaire create/edit, каждый section/error/keyboard;
- session questionnaire и все conditional варианты;
- Overview: no dog, dog, empty history, long history;
- scan idle/scanning/results/error;
- preflight camera/BLE;
- permission denied;
- preview ready;
- starting;
- recording;
- finalizing;
- completed/failed/interrupted;
- sync pending/uploading/progress/synced/permanent error;
- remote-only/downloading/downloaded;
- Charts: empty/live/history/fullscreen/pan/zoom;
- Settings: server disconnected/connected/revoked/error;
- export bottom sheet с доступными/недоступными artifacts.

### 11.2. Device matrix

Минимум:

| Класс | Размер/API |
|---|---|
| small phone | Pixel 2/API 31 |
| current phone | API target/current |
| narrow portrait | 320–360 dp width |
| landscape | small и current |
| cutout/insets | physical/emulator profile |
| tablet | только если реально поддерживается |

### 11.3. Presentation matrix

Для ключевых экранов:

- English/Russian;
- light/dark;
- font scale 1.0/1.3/1.5/2.0;
- display size default/large;
- portrait/landscape;
- keyboard hidden/open;
- gesture/3-button navigation.

### 11.4. Автоматические semantic bounds checks

Для каждого screenshot state:

- node bounds внутри root/insets;
- кликабельная область минимум 48 dp;
- Stop полностью видим;
- нет пересечения top bar/content/bottom navigation;
- текст primary action не обрезан;
- horizontal scroll не требуется для формы;
- selected state имеет text/semantics, а не только цвет;
- TalkBack label присутствует у icon-only controls;
- error связан с полем.

Screenshot comparison используется как сигнал, но каждое изменение baseline
просматривает человек. Pixel diff не заменяет семантические assertions.

## 12. Security and privacy

- release не разрешает cleartext;
- debug cleartext ограничен local host;
- token не лежит в git, logs, questionnaire JSON или Android backup;
- revoked token перестаёт работать;
- server rejects oversized JSON/chunk/file;
- path traversal corpus;
- malformed multipart/chunk headers;
- authorization на каждый dog/recording/artifact;
- logs содержат ids/status, но не полную анкету/video bytes/token;
- backup rules исключают token и большие локальные recordings;
- consent/privacy text для видео проверен на обоих языках.

## 13. Reliability, performance and capacity

Измерить, не угадывать:

- packet loss/queue depth во время записи с видео;
- CPU/memory/battery 30 минут;
- free-space estimate до Start;
- memory во время hash/upload/download 1+ GiB;
- API latency per chunk;
- PostgreSQL connections;
- concurrent uploads ожидаемого числа телефонов;
- disk growth и alert threshold;
- WorkManager delay under Doze/network changes.

Критерий: capture не ждёт сеть, файлы читаются streaming, приложение не держит
video целиком в памяти.

## 14. Backup and disaster recovery

Отдельный restore drill:

1. создать несколько complete recordings;
2. сделать PostgreSQL backup и filesystem snapshot;
3. восстановить в новом окружении;
4. запустить migrations/readiness;
5. выполнить integrity scan;
6. скачать каждый artifact type;
7. сравнить receipt/hash;
8. зафиксировать RPO/RTO и недостающие операции.

Backup без успешного restore drill не считается защитой.

## 15. Финальный release gate

Release готов только когда одновременно:

- Android fast/full зелёные;
- server unit/integration/E2E зелёные;
- clean Docker deploy и persistent restart зелёные;
- v2 migration fixture reconciled;
- upload/retry/restart/hash checks зелёные;
- clean-device restore/download зелёный;
- visual matrix без blocker overlap/clipping;
- physical BLE/camera/MP4/sync протокол выполнен;
- backup restore drill выполнен;
- одна реальная сессия проходит путь от полной анкеты до server receipt и
  byte-identical download.

Все исключения перечисляются в acceptance report. Формулировка «не проверяли,
но должно работать» не является pass.
