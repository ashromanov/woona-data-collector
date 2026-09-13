# Окончательные модели: поля и отношения

## 1. `client_devices`

Доверенная установка Android и единица отзыва доступа.

| Поле | Смысл |
|---|---|
| `id` | UUID установки, создаётся/provision на сервере |
| `label` | понятное имя телефона/бригады |
| `token_hash` | SHA-256/подходящий односторонний hash bearer token; сам token не хранится |
| `created_at` | регистрация |
| `last_seen_at` | последний успешный request |
| `revoked_at` | отзыв доступа; null означает active |
| `app_version` | последняя известная версия |
| `notes` | административное примечание |

Отношения:

- один device создаёт много profile versions;
- один device создаёт много recordings.

## 2. `dogs`

Стабильная идентичность собаки, не историческая анкета.

| Поле | Смысл |
|---|---|
| `id` | UUID, создаётся Android offline |
| `number_or_name` | актуальное отображаемое имя/номер |
| `revision` | optimistic concurrency |
| `created_by_device_id` | происхождение |
| `created_at`, `updated_at` | server audit |
| `archived_at` | soft archive; история не удаляется |

Физическое удаление собаки с recordings запрещено.

## 3. `dog_profile_versions`

Immutable snapshot полной анкеты собаки.

| Поле | Смысл |
|---|---|
| `id` | UUID версии |
| `dog_id` | владелец |
| `schema_version` | версия JSON Schema |
| `validation_state` | `complete` или historical `legacy_incomplete` |
| `questionnaire` | JSONB по dog schema |
| `content_sha256` | canonical JSON hash, идемпотентность |
| `created_by_device_id` | кто создал |
| `client_created_at` | время на телефоне |
| `server_created_at` | время приёма |
| `superseded_at` | null только у текущей версии |

У собаки ровно одна текущая версия благодаря partial unique index. Recording
ссылается на конкретную версию, поэтому позднее редактирование не меняет
историю.

## 4. `recordings`

Главная сущность одной live/replay сессии.

| Поле | Смысл |
|---|---|
| `id` | UUID сессии, создаётся offline |
| `dog_id` | собака |
| `dog_profile_version_id` | анкета собаки на момент сессии |
| `capture_device_id` | телефон |
| `source` | `live`/`replay` |
| `capture_status` | локальный результат capture |
| `ingest_status` | серверная полнота |
| `started_at`, `ended_at` | фактический UTC interval |
| `timezone` | IANA zone для UI/audit |
| `session_label` | человеческая подпись, не UUID |
| `questionnaire_schema_version` | версия session schema |
| `questionnaire_validation_state` | complete/legacy |
| `session_questionnaire` | immutable JSONB snapshot |
| `video_requested` | обязательный preflight выбор |
| `sensor_hardware_id` | идентификатор датчика, если доступен |
| `app_version`, `protocol_version` | воспроизводимость |
| `capture_error_*` | terminal причина |
| `client_created_at` | offline creation |
| `server_*` | audit/receipt |
| `receipt_sha256` | hash canonical verified manifest |

`capture_status` и `ingest_status` не объединяются: запись может быть
`capture_status=completed`, но ещё `ingest_status=uploading`.

## 5. `recording_sync`

Одна строка timing metadata на recording.

Группы полей:

- session zero: UTC/wall/monotonic/uncertainty;
- первый/последний sensor packet;
- sensor device timer;
- video requested/recorder started/first frame;
- camera source и первый MP4 sample PTS;
- вычисленное смещение;
- quality по camera, sensor и overall;
- calibration offset и будущая оценка drift.

Отношение строго 0..1 к recording: replay/legacy может иметь неполную sync row,
но новая сессия всегда создаёт её.

## 6. `artifacts`

Manifest и upload state одного файла.

| Поле | Смысл |
|---|---|
| `id` | immutable UUID файла |
| `recording_id` | владелец |
| `artifact_type` | packet/raw/video/... |
| `file_name` | display name, без path |
| `mime_type` | ожидаемый MIME |
| `expected_size_bytes` | локальный финальный размер |
| `stored_size_bytes` | подтверждённый server offset |
| `sha256` | локальный ожидаемый и серверно проверяемый hash |
| `storage_status` | pending/uploading/available/error |
| `server_relative_path` | API-generated path после finalize |
| timestamps | client/server/upload/verify audit |
| `error_*` | последняя permanent data/storage причина |

`available` требует полного размера, server path и verify timestamp.

## 7. Structured vs files

PostgreSQL:

- все поля таблиц;
- обе questionnaire JSON;
- все timing anchors/quality;
- artifact size/hash/status/path;
- error/receipt.

Server filesystem:

- `packets.bin`;
- `packet_timeline.bin`;
- `raw_fragments.binlog`;
- `diagnostics.log`;
- `channel.csv`, если создан;
- `video.mp4`, если выбран;
- `sync.json`;
- replay source, если его provenance нужно сохранить.

ZIP не является каноническим artifact. Он создаётся только по запросу export.

## 8. Delete policy

- device: revoke, не delete;
- dog: archive;
- profile version: immutable;
- recording: soft-delete добавлять только при реальном product requirement;
- artifact: immutable после available;
- retention/physical purge не входит в первую реализацию.

## 9. Локальная SQLite

Точная final DDL находится в `sqlite.sql`. Она повторяет серверные идентификаторы
и отношения, но добавляет только необходимые устройству поля:

- `dogs.server_revision` — последняя подтверждённая server revision;
- `dog_profile_versions.server_sync_state` — отдельная выгрузка профиля даже
  без recording;
- `recordings.relative_directory` — безопасная локальная папка сессии;
- `artifacts.relative_path/local_presence` — local, remote-only, both, missing;
- `artifacts.upload_state/uploaded_bytes` — подтверждённый resumable progress;
- `server_sync_state` — одна строка receipt/error/retry на recording.

Локальные и серверные UUID одинаковы. Server paths никогда не становятся
локальными source paths и наоборот.

SQLite хранит questionnaire как `TEXT`; валидность проверяют typed Android
serializer/validator до transaction и PostgreSQL API повторно на trust
boundary. Локальная DDL не зависит от наличия SQLite JSON extension.
