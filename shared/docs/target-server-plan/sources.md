# Внешние технические основания

Документация проекта остаётся источником целевого контракта. Ниже перечислены
внешние первичные источники, на которых основаны только платформенные решения.

## Android

- [CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata.html#SENSOR_INFO_TIMESTAMP_SOURCE) —
  источник camera timestamp: `REALTIME` можно сопоставлять с
  `elapsedRealtimeNanos`; при `UNKNOWN` нельзя предполагать общий timebase между
  подсистемами.
- [WorkManager](https://developer.android.com/reference/androidx/work/WorkManager.html) —
  persistent deferrable work, constraints, unique work и retry для выгрузки
  после локального завершения записи.
- [Offline-first data layer](https://developer.android.com/topic/architecture/data-layer/offline-first) —
  локальный источник данных и очередь синхронизации при отсутствии сети.

## PostgreSQL

- [PostgreSQL data types](https://www.postgresql.org/docs/current/datatype.html) —
  нативные `uuid`, `jsonb`, `timestamptz` и ограничения типов целевой модели.

## Docker

- [Docker Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/) —
  healthcheck и ожидание `service_healthy`; простой порядок запуска контейнеров
  сам по себе не означает готовность PostgreSQL.

## Ограничение источников

Ни одна из ссылок не доказывает точность физической синхронизации конкретного
BLE-датчика и телефона. Это доказывается только протоколом из
`verification-plan.md` на реальном железе.
