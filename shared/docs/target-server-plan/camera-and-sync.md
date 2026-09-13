# Камера, датчик и единая шкала времени

## 1. Принцип

«Одна timezone и одинаковые timestamp» недостаточно для точной синхронизации.

Нужно разделять:

- `UTC` — абсолютное время для сервера и человека;
- IANA timezone — отображение локального времени;
- Android monotonic clock — вычисление интервалов и camera/sensor offset;
- sensor device timer — внутренняя шкала датчика;
- MP4 presentation timestamps — шкала кадров контейнера.

UTC может прыгнуть из-за NTP/ручной смены часов. Timezone вообще не задаёт
физический clock. Поэтому все offset считаются в nanoseconds monotonic, а UTC
прикладывается как audit anchor.

Android официально определяет camera timestamp source `REALTIME` как
сопоставимый с `SystemClock.elapsedRealtimeNanos()`. При source `UNKNOWN`
camera timestamp нельзя объявлять точно сопоставимым с другими subsystems;
нужно использовать callback estimate и снижать quality.

## 2. Целевой UX

### 2.1. Preflight

После обязательной анкеты показывается один экран:

```text
Собака: Rex
Сессия: контроль после прогулки

[ BLE ] Подключён / notifications готовы
[ Видео ] Включено
[ 16:9 live camera preview                  ]
[ Камера ] ready, back, 1920×1080, 30 fps
[ Память ] достаточно

[ Начать запись ]
```

Start появляется после BLE readiness. До фактического запуска проверяются:

- анкеты не complete;
- BLE не ready;
- camera permission при `videoRequested=true`;
- уже выполняется другая запись.

Server availability Start не блокирует. Ошибка открытия/reconfigure камеры после
старта переводит UI в явный degraded mode, но не выбрасывает уже идущие данные
датчика.

### 2.2. Recording

```text
[ RED ● 00:01:42 ]
[ live camera preview                       ]
Sensor: recording · packets 1420 · gaps 0
Video: recording · 1080p30
Sync: locked / estimating / degraded

[ Завершить запись ]
```

Отдельных `Start video`/`Stop video` нет.

### 2.3. Finalizing

После Stop:

```text
Завершение записи
✓ BLE приём остановлен
✓ packets/raw/log закрыты
… MP4 финализируется
… SHA-256
```

Повторный Stop идемпотентен. Navigation/back не запускает вторую финализацию.
После результата показывается:

- duration;
- packets/loss/rejected;
- video duration/size;
- sync quality и offset;
- local save;
- server sync state.

## 3. Capture state machine

Одна UI state machine владеет датчиком и камерой:

```text
IDLE
WAITING_FOR_SENSOR
READY
STARTING
RECORDING
DEGRADED
STOPPING
FINISHED
FAILED
```

Недопустимые операции:

- start из RECORDING;
- edit questionnaire после STARTING;
- отдельный camera stop;
- смена собаки;
- replay/live одновременно;
- upload открытого artifact.

### STARTING и частичный отказ

Sensor capture запускается первым. Для video session `RECORDING` показывается
после первого camera callback; sensor-only переходит сразу. Camera start timeout
равен 10 секундам.

- ошибка до запуска оставляет возможность исправить permission и повторить;
- ошибка камеры после запуска sensor переводит состояние в `DEGRADED`;
- `videoRequested` не переписывается в false;
- та же Stop-кнопка завершает sensor files и manifest с
  `capture_error_code=camera_failed`.

## 4. Camera preview без новой библиотеки

Текущий Camera2/MediaRecorder сохраняется.

Минимальное расширение:

1. Compose `AndroidView` содержит platform `SurfaceView`;
2. lifecycle surface передаётся `AndroidVideoRecorder`;
3. camera capture session получает два targets:
   - preview surface;
   - recorder surface, только во время записи;
4. до Start существует preview-only session;
5. на Start session безопасно reconfigure в preview+recorder;
6. после Stop recorder и preview session закрываются, а preview исчезает с
   финального состояния;
7. `SurfaceView` aspect ratio соответствует выбранному video size;
8. rotation/mirroring обрабатываются в preview, MP4 получает orientation hint.

CameraX не добавляется на первом шаге: существующий Camera2 уже работает, а
native preview surface решает запрос. CameraX стоит рассмотреть только если
reconfigure/lifecycle compatibility на реальном device matrix станет
измеренной проблемой.

## 5. Sensor timestamp path

### 5.1. Где ставить timestamp

Monotonic receive timestamp снимается максимально рано в
`onCharacteristicChanged`, до queue и file I/O:

```text
receivedMonotonicNs = SystemClock.elapsedRealtimeNanos()
receivedWallClockMs = System.currentTimeMillis()
```

Listener передаёт не только bytes, а маленький value object:

```text
BleFragment(
  bytes,
  receivedMonotonicNs,
  receivedWallClockMs
)
```

Это изменение идёт по фактическому fragment path один раз, а не добавляется
отдельно в UI/camera.

### 5.2. Raw format v2

Новый `BLERAW2` record:

```text
sequence: int64
receivedWallClockMs: int64
receivedMonotonicNs: int64
payloadLength: int32
payload: bytes
```

Parser обязан читать v1 и v2. Writer создаёт только v2.

### 5.3. Accepted packet timeline

Для каждого accepted packet записывается sidecar `packet_timeline.bin`:

```text
sequence
packetCounter
sensorTimerMs
hostWallClockMs
hostMonotonicNs
packetBytes
```

Не менять `packets.bin`: это существующий канонический raw packet dump и его
формат уже используется parser/export. Sidecar — меньший риск.

`firstAcceptedSensorPacketMonotonicNs` становится sensor anchor. Событие
`notifications enabled` остаётся диагностикой, но не sensor zero.

## 6. Camera timestamp path

### 6.1. Первый frame

Не принимать callback до фактического `MediaRecorder.start()`.

Минимальная схема:

1. reconfigure preview+recorder;
2. начать repeating request;
3. вызвать `MediaRecorder.start()`;
4. atomic flag `recorderStarted=true`;
5. первый `onCaptureStarted` после flag сохраняет camera timestamp;
6. одновременно сохранить callback `elapsedRealtimeNanos`;
7. после Stop проверить MP4 первым sample PTS через `MediaExtractor`.

Сохранить:

```text
videoRequestedMonotonicNs
mediaRecorderStartedMonotonicNs
videoFirstFrameCameraTimestampNs
videoFirstFrameCallbackMonotonicNs
videoFirstFrameMonotonicNs
cameraTimestampSource
videoFirstSamplePtsUs
```

### 6.2. Quality

| Quality | Условие |
|---|---|
| hardware_monotonic | camera source REALTIME, first sensor packet timestamped, MP4 valid |
| arrival_aligned | camera comparable, sensor физически привязан только по BLE arrival |
| callback_estimate | camera source UNKNOWN, используется callback monotonic |
| degraded | отсутствует часть anchors, но artifacts сохранены |
| unavailable | sync вычислить нельзя |

`hardware_monotonic` не должно означать hardware-sync датчика. Лучше итоговое
поле иметь составным:

```text
cameraClockQuality
sensorClockQuality
overallSyncQuality
```

Для текущего BLE без общей аппаратной линии realistic overall quality —
`arrival_aligned`.

## 7. Маппинг sensor device timer

Первый рабочий вариант:

```text
sensorHostAnchorNs = firstAcceptedPacketLastFragmentNs
sensorDeviceAnchorMs = firstAcceptedPacket.deviceTimerMs

estimatedHostNs(sample) =
  sensorHostAnchorNs +
  (sampleDeviceTimerMs - sensorDeviceAnchorMs) * 1_000_000
```

Обязательно хранить raw arrivals, чтобы формулу можно было улучшить.

Ограничение: первый arrival включает BLE latency.

После реальных измерений можно добавить linear fit:

```text
hostNs ≈ offsetNs + scale * sensorTimerMs
driftPpm = (scale / 1_000_000 - 1) * 1_000_000
```

Не добавлять fit до:

- достаточного числа packet pairs;
- обработки timer wrap/reset;
- измеренного улучшения против простого anchor;
- зафиксированного acceptable sync error.

Калибровочный offset должен оставаться настраиваемым, потому что реальный
датчик и BLE имеют систематическую задержку.

## 8. UTC anchor

При Start снять bracket sample:

```text
monoBefore
wallClockMs
monoAfter
sessionZeroMonotonicNs = midpoint
uncertaintyNs = (after - before) / 2
```

UTC любого monotonic события:

```text
utc(event) =
  Instant.ofEpochMilli(sessionZeroWallClockMs) +
  (eventMonotonicNs - sessionZeroMonotonicNs)
```

При долгой сессии можно снять ещё один end anchor и обнаружить wall-clock jump,
но offset всё равно считать по monotonic.

## 9. Stop/finalization

Одна suspend/blocking orchestration операция:

1. state -> FINALIZING;
2. запретить новые fragments;
3. stop BLE notifications/disconnect;
4. drain packet queue с timeout;
5. flush packet/raw/timeline/diagnostic;
6. stop MediaRecorder на camera thread;
7. проверить MP4 контейнер:
   - существует;
   - size > 0;
   - video track существует;
   - first sample PTS доступен;
8. записать final sync metadata;
9. atomic write `sync.json`;
10. вычислить artifact sizes/SHA-256;
11. SQLite transaction:
    - terminal capture status;
    - endedAtUtc;
    - artifact rows;
    - sync row;
    - server sync pending;
12. enqueue unique WorkManager;
13. state -> COMPLETED/INTERRUPTED/FAILED.

Если video stop падает:

- sensor files всё равно закрываются;
- recording не теряется;
- status failed/interrupted с error code;
- повреждённый MP4 не помечается available;
- metadata и остальные artifacts всё равно выгружаются.

## 10. Физическая проверка синхронизации

Нужен минимум один Android phone, реальный sensor и видимое событие, которое
фиксируют оба канала.

Пример:

- резкий удар/встряхивание датчика в поле зрения камеры;
- LED/механический импульс, если sensor канал его видит;
- 10 событий в начале, середине и конце 10–20 минутной записи.

Для каждого:

1. определить sensor peak timestamp;
2. определить video frame timestamp;
3. посчитать offset;
4. дать median, p95, max absolute error;
5. оценить drift от начала к концу;
6. повторить минимум на двух телефонах/камерах;
7. отдельно REALTIME и UNKNOWN camera source.

Acceptance threshold нельзя выдумывать из кода. Его утверждают до реализации
или после первого baseline measurement. До него критерий: метрики вычислены,
raw evidence сохранён, качество не завышено.
