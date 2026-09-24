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

## TestFlight через CI

Пуш изменений в `ios/` в `main` автоматически запускает workflow
`Upload iOS to TestFlight`. Номер сборки вычисляется из номера запуска CI и
его попытки, поэтому вручную менять `CURRENT_PROJECT_VERSION` не нужно.
Ручной запуск с `upload=false` проверяет подпись и экспорт без загрузки.
Внешняя группа TestFlight получает новую сборку после отдельного добавления
сборки в группу и, если требуется, проверки Apple.

Секреты GitHub Actions: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY`
(ключ App Store Connect с ролью Developer), `BUILD_CERTIFICATE_BASE64`,
`BUILD_PROVISION_PROFILE_BASE64`, `P12_PASSWORD`, `KEYCHAIN_PASSWORD`.
Сертификат и профиль подписи истекают 24 сентября 2027 года; перед этим
перевыпустите их и обновите соответствующие секреты.
