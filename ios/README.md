# Woona iOS

Нативный SwiftUI-клиент iOS 17+, восстановленный из старого проекта и
синхронизированный с текущим Android-контрактом. Внешних packages нет.

## Реализованный паритет

- Overview / Charts / Settings, EN/RU и system/light/dark;
- обязательные typed-анкеты собаки и сессии с conditional validation;
- несколько собак, immutable profile versions и последние 10 записей;
- CoreBluetooth scan/connect, live capture и replay старых/новых dump;
- `BLERAW2`, `packets.bin`, `packet_timeline.bin`, diagnostics и export/share;
- задняя AVCaptureSession camera, H.264 MP4, 16:9 preview и единый Start/Stop;
- `ios.CMClock.hostTime`, первый video PTS и `sync.json`;
- SQLite schema v5, Keychain token, общий Android/iOS сервер;
- resumable `HEAD/PATCH` upload, metadata restore, проверяемый Range download
  через `.part` и BGProcessing retry.

## Проверка на Mac

```bash
xcodebuild -project Woona.xcodeproj -scheme Woona \
  -destination 'platform=iOS Simulator,OS=latest,name=iPhone 16' test
```

В Debug локальный HTTP разрешён только для local networking; Release ожидает
HTTPS. Bearer token хранится в Keychain, URL и Wi-Fi policy — в UserDefaults.

Затем на подписанном физическом iPhone проверить BLE, camera permission,
preview/MP4, 10-с/1/10/30-минутные записи, background/lock/interruption,
upload resume и восстановление Android-записи. Linux не содержит Swift/Xcode,
поэтому не доказывает компиляцию или аппаратное поведение.
