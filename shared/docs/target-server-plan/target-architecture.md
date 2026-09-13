# Окончательная архитектура

## 1. Цель

После миграции приложение должно:

- создавать и редактировать собак локально даже без сети;
- записывать BLE и видео без зависимости от доступности сервера;
- безопасно завершать локальные файлы до начала выгрузки;
- автоматически передавать все структурированные данные в PostgreSQL;
- автоматически передавать все артефакты в файловую систему сервера;
- возобновлять большие передачи после потери сети/process death;
- показывать пользователю, что локально сохранено, что выгружается и что
  сервер подтвердил;
- после reinstall загружать с сервера профили и историю, а артефакты скачивать
  по запросу;
- проверять размеры и SHA-256 в обе стороны.

## 2. Минимальный состав

### Android

Сохраняется один существующий `app` module и текущие package boundaries.

Новые/изменённые обязанности:

- `data/` — SQLite final schema, immutable profile versions, sync state;
- `server/` или `sync/` package — HTTP client, manifest DTO и sync worker;
- `feature/device/` — единая capture state machine;
- `video/` — preview surface + recorder;
- `profile/` — schema-driven validation без универсального form engine;
- `storage/` — monotonic raw format v2, hashes и artifact validation.

Не нужен новый Android module, repository framework или DI framework.

### API

Один контейнер с одним HTTP API:

- Python + FastAPI;
- Pydantic request validation;
- SQLAlchemy Core/ORM только для PostgreSQL access;
- Alembic для миграций;
- потоковая запись chunks обычными файловыми операциями;
- без background queue: Android сам повторяет передачу, а API выполняет один
  короткий chunk request.

Это исходная реализация, а не вечное ограничение. Отдельный upload service
нужен только если один API-процесс перестанет выдерживать измеренную нагрузку.

### PostgreSQL

Локальный Docker baseline: PostgreSQL 18 с healthcheck.

PostgreSQL хранит:

- доверенные Android device identities;
- собак и immutable версии анкет;
- записи и session questionnaire snapshot;
- clock anchors и вычисленное качество синхронизации;
- artifact manifests, upload offsets, hashes и серверные receipts;
- статусы capture/ingest.

`uuid`, `timestamptz` и `jsonb` используются нативно. Все server timestamps
хранятся как `timestamptz`; IANA timezone хранится отдельным текстовым полем
только для восстановления локального представления.

### Файловая система

Один server storage root, например:

```text
/srv/woona/
├── incoming/
│   └── <artifact-id>.part
└── recordings/
    └── <first-two-hex-of-recording-id>/
        └── <recording-id>/
            └── <artifact-id>/
                └── <server-chosen-file-name>
```

Правила:

- клиент не передаёт абсолютный путь;
- API сам выбирает конечный относительный путь;
- исходное имя хранится отдельно как display metadata;
- `..`, slash, NUL и неожиданные extension не участвуют в server path;
- `.part` никогда не считается доступным artifact;
- после проверки размера и SHA-256 выполняется atomic rename на той же ФС;
- доступный artifact immutable; замена требует нового artifact UUID;
- БД хранит только относительный путь;
- периодический integrity job сверяет БД, файлы, size и hash.

## 3. Разделение источников истины

### Во время записи

Локальные SQLite и files являются единственным источником истины. Сеть не
участвует в capture critical path.

### После server receipt

Сервер является источником восстановления. Локальная копия остаётся кэшем и
оригиналом до отдельной будущей retention policy.

В первой версии автоматическое удаление локальных артефактов отсутствует.
Причина: это самый безопасный и короткий путь; очистку можно добавить после
измерения storage pressure и доказанного restore.

### Конфликты профилей

Анкета собаки versioned и immutable:

1. редактирование создаёт новый `dog_profile_versions.id`;
2. старая версия получает `superseded_at`;
3. уже начатые записи навсегда ссылаются на старую версию;
4. новая запись использует текущую версию;
5. обновление `dogs.revision` требует ожидаемую revision;
6. при concurrent edit API возвращает `409`, приложение не делает silent
   last-write-wins.

Это сохраняет научную воспроизводимость без копирования каждой колонки анкеты
в каждую запись.

## 4. Локальная final model

SQLite остаётся, но перестаёт быть единственным хранилищем.

Минимальные локальные сущности:

- `dogs`;
- `dog_profile_versions`;
- `recordings`;
- `recording_sync`;
- `artifacts`;
- `server_sync_state`.

`dog_profile_versions` дополнительно хранит собственное состояние server sync:

```text
server_sync_state: pending | uploading | synced | retryable_error | permanent_error
server_revision
attempt_count
last_error_code
last_error_message
server_synced_at_utc
```

Это нужно, потому что новый/отредактированный профиль обязан попасть на сервер,
даже если после редактирования ещё не было ни одной записи. Отдельная generic
event queue не нужна: profile sync fields живут в profile version, recording
sync fields — в `server_sync_state`.

`server_sync_state` не является универсальной event sourcing очередью. Это одна
строка на запись:

```text
recording_id
state: pending | uploading | synced | retryable_error | permanent_error
attempt_count
next_retry_at_utc
last_error_code
last_error_message
server_receipt_at_utc
updated_at_utc
```

Artifact row дополнительно хранит:

```text
sha256
expected_size_bytes
uploaded_bytes
upload_state
server_relative_path
server_verified_at_utc
```

WorkManager ищет незавершённую запись по этим полям. Отдельная generic
operations table не нужна.

При сохранении профиля ставится unique work
`server-profile-<profile-version-id>`. Версии одного dog отправляются по
`client_created_at`, чтобы server revision менялась последовательно. Recording
worker сначала подтверждает связанную profile version, а затем отправляет
recording manifest. Сам manifest всё равно содержит snapshot профиля как
идемпотентную страховку от пропущенной отдельной work.

## 5. Authentication и transport security

### Local Docker

- API слушает host loopback/LAN port;
- используется отдельный debug token из `.env`, не зашитый в git;
- Android emulator обращается к host через `10.0.2.2`;
- cleartext разрешается только debug network security config и только для
  указанного local host.

### Production

- только HTTPS с валидным сертификатом;
- PostgreSQL и storage root не публикуются наружу;
- у каждого Android installation свой bearer token;
- сервер хранит только token hash;
- token provision выполняется администратором и может быть отозван;
- токен хранится на Android через platform Keystore;
- логи не содержат bearer token, полную анкету или binary body;
- server endpoints проверяют ownership/device identity и лимиты размера;
- filename и MIME не считаются доверенными;
- request body и chunk size ограничены;
- failed authentication никогда не переводится WorkManager в бесконечный retry:
  это permanent error с видимым действием «Переподключить сервер».

## 6. Docker local environment

Итоговый `compose.yaml` содержит только:

```text
api
postgres
```

Persistent mounts:

```text
postgres_data -> /var/lib/postgresql/data
../var/storage -> /srv/woona
```

Порядок:

1. PostgreSQL healthcheck через `pg_isready`;
2. migration job/API startup применяет Alembic migrations;
3. API readiness становится healthy только после DB ping, schema version check
   и write/read probe storage root;
4. Android E2E запускается только после API readiness.

Local test должен переживать:

```text
docker compose restart api
docker compose restart postgres
docker compose down
docker compose up -d
```

без потери ранее подтверждённых данных и файлов.

`docker compose down -v` является только явной destructive test cleanup
операцией и не используется обычными check scripts.

## 7. Production deployment

Минимум:

- один API container/process;
- managed или отдельный PostgreSQL 18;
- отдельный persistent storage volume;
- TLS reverse proxy;
- ежедневный `pg_dump`/physical backup по выбранной политике;
- snapshot/backup файловой системы;
- restore drill на отдельном окружении;
- мониторинг свободного места, 5xx, auth failures, checksum failures,
  незавершённых `.part` и записей `ingest_status != complete`.

Артефакты immutable, поэтому backup DB и файлов можно сверить integrity job.
Если требуется строго point-in-time атомарная копия БД+ФС, добавляется короткое
окно приостановки finalize операций. До появления такого требования не нужен
distributed transaction.

## 8. Google Drive cutover

Удаление Drive выполняется не в начале:

1. server sync добавлен параллельно;
2. локальная миграция и server restore доказаны;
3. несколько реальных сессий одновременно присутствуют локально и на сервере;
4. скачанные файлы совпадают SHA-256;
5. после этого Drive auto-upload выключается;
6. затем удаляются Drive authorization, settings, workers, strings и tests;
7. Android manual Share остаётся.

В итоговом UI Settings вместо Drive:

- server URL/окружение;
- connection/auth status;
- «только Wi-Fi»;
- pending/failed/synced counts;
- last successful sync;
- retry;
- безопасное переподключение токена.

## 9. Необходимые observability fields

Каждый API response получает `request_id`. Сервер логирует:

- request id;
- device id;
- endpoint;
- recording/artifact id;
- bytes before/after;
- result code;
- duration;
- error code без questionnaire body.

Android diagnostic log фиксирует:

- recording id;
- WorkManager id;
- artifact id/type;
- upload offset;
- HTTP status;
- retry/permanent classification;
- server receipt.

Этого достаточно без внешней log platform на первом этапе.
