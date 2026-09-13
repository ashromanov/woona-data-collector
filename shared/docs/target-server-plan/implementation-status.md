# Статус реализации

Дата: 2026-08-17

## Реализовано

### Android/iOS data и анкеты

- Android и iOS используют SQLite schema version 5: dogs, immutable profile
  versions, recordings, recording sync, artifacts и server sync state.
- Android выполняет транзакционную миграцию v2 с сохранением UUID, путей и старого JSON.
  Legacy-данные помечаются `legacy_incomplete`, а SHA файлов считается
  отдельным streaming worker после открытия БД.
- Обе новые анкеты сериализуются typed-моделями schema version 1.
- Каждый вопрос требует явного ответа; неизвестные и неизмеренные значения
  представлены enum, а не пустой строкой.
- Parent selection очищает несовместимые child values. Activity group и
  location ограничивают допустимые activity/surface.
- Radio не снимается повторным нажатием; отображаются progress, inline errors,
  общий error count и первый invalid field прокручивается в viewport.
- Draft сохраняется через Android saved-state; iOS не сохраняет незавершённый
  sheet после принудительного завершения приложения.
- Каждая recording ссылается на точную profile version.

### Capture и камера

- Один Start запускает датчик и выбранное видео; один Stop завершает оба
  источника, дожидается очереди, регистрирует файлы и запускает sync.
- Camera2 preview остаётся видимым во время MediaRecorder capture.
- Валидированный первый BLE-пакет получает wall UTC, monotonic timestamp,
  packet counter и device timer. Каждый принятый пакет сохраняется в
  `packet_timeline.bin`.
- Видео сохраняет recorder-start, camera timestamp, callback monotonic,
  first-frame offset, первый MP4 sample PTS и stop/duration metadata.
- Camera timestamp source определяет `hardware_monotonic` или
  `callback_estimate`; неизвестный source не выдаётся за аппаратную точность.
- Ошибка камеры после старта оставляет sensor capture работающим в состоянии
  `DEGRADED`; manifest содержит `camera_failed`, завершение остаётся одной
  кнопкой.

### Сервер и синхронизация

- Docker Compose поднимает PostgreSQL 18 и один FastAPI service.
- Bearer token хранится в БД как SHA-256; отдельные device identity и revoke
  сохранены. Активные Android/iOS tokens читают общую базу, а незавершённую
  artifact upload изменяет только capture device.
- Сервер принимает `android.elapsedRealtimeNanos` и `ios.CMClock.hostTime`, а
  для камеры — `realtime` и `avfoundation_session_clock`.
- Profile PUT использует optimistic revision и immutable versions.
- Recording manifest и questionnaires повторно валидируются на сервере.
- Artifact upload возобновляется через HEAD/PATCH offset, ограничивает размер
  chunk, проверяет filename/type/MIME/size/SHA и завершает файл atomic rename.
- Recording receipt выдаётся только после доступности всех заявленных файлов.
- Download поддерживает Range, ETag, `.part`, SHA и отсутствие молчаливого
  overwrite.
- Restore возвращает текущие и все исторические profile versions, recordings,
  sync и artifacts. Совпадающие локальные данные становятся `both`, конфликт
  SHA не перезаписывается.
- Readiness отдельно проверяет PostgreSQL и файловую систему и возвращает 503
  с отдельным кодом недоступной зависимости.
- Google Drive код, зависимости, permissions и фоновые workers удалены.

## Ограничение доказательства

Managed Android emulator проверяет Compose, SQLite, migration, fake BLE и
настоящий HTTP round-trip с Docker. В текущей Linux-среде нет Xcode, поэтому
iOS target и XCTest ещё должны быть собраны на Mac. Аппаратный BLE transport,
конкретная камера, читаемость MP4 и фактическая погрешность синхронизации на
обеих платформах требуют физических устройств и целевого датчика.

## Итоговая проверка

Фактические результаты Android-сборок, 118 JVM-тестов, двух прогонов по 31 Android
instrumentation test, 13 серверных clean-room тестов, restart,
backup/restore, auth/readiness и 24 визуальных состояний собраны в
[acceptance-report.md](acceptance-report.md).
