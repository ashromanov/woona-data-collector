# Woona labeling deployment

The production route is `https://cool-trams.digital/`. Caddy sends `/v1/*`
and `/health/*` to the existing FastAPI ingest service and all other paths to
Label Studio. Android and iOS keep their existing HTTPS resumable upload and
receipt protocol. A worker imports only server-verified recordings into Label
Studio every 60 seconds. No MinIO is needed: the existing versioned PostgreSQL
metadata and SHA-256-verified filesystem storage already provide the ingest
contract; Label Studio gets read-only local-file mounts. The two PostgreSQL
databases are separate and not exposed to the internet.

## Fresh host

From `shared/` on a new Ubuntu host with Docker Compose:

```bash
python3 tools/bootstrap_label_env.py
install -d -m 0755 /srv/woona/storage /srv/woona/postgres \
  /srv/woona/label-postgres /srv/woona/drive-2026-09-18
chown 10001:10001 /srv/woona/storage
docker compose --env-file .env --env-file .env.label \
  -f compose.yaml -f compose.label.yaml up -d --build --wait
```

The bootstrap command creates root-only `.env` and `.env.label`. It refuses to
overwrite either file. The Label Studio admin login is
`admin@cool-trams.digital`; its password and the API/device tokens stay only in
those files. Configure a device token in the app settings; never put it in an
APK. After restoring a storage archive, grant Label Studio UID 1001 traversal
with `setfacl -m u:1001:rx /srv/woona/storage` if that root is mode 700.

## Drive snapshot

`data/drive-2026-09-18.json` pins all 364 source file IDs, original paths,
MIME types and sizes from the supplied Drive folder. The download leaves
source bytes unchanged; `checksums.json` records their SHA-256 and
`recordings-v1.json` groups the 51 BLE sessions by source ID, date and a
normalized dog name. An unknown dog stays `unknown`. Each derived metadata
file has `schema_version: 1`. The worker imports the normalized index only
after all files have arrived and passed the size/checksum checks.

```bash
python3 -m venv /opt/woona-tools
/opt/woona-tools/bin/pip install gdown==6.4.0
python3 tools/download_drive_snapshot.py data/drive-2026-09-18.json \
  /srv/woona/drive-2026-09-18 --gdown /opt/woona-tools/bin/gdown
cp data/drive-2026-09-18.json /srv/woona/drive-2026-09-18/manifest.json
python3 tools/normalize_drive_snapshot.py /srv/woona/drive-2026-09-18
setfacl -R -m u:1001:rX /srv/woona/drive-2026-09-18/raw
```

Download is resumable: verified files are skipped. If Google blocks a public
download, use an authenticated local Google session and copy the completed
files over SSH, then run `python3 tools/download_drive_snapshot.py
data/drive-2026-09-18.json /srv/woona/drive-2026-09-18 --verify-only`
before normalizing. This re-hashes every transferred file and writes one
complete checksum map. Do not copy browser cookies or account tokens to the
server.

The ACL grants the Label Studio container read-only access to the private
snapshot; repeat it after any later file transfers.

## Labeling and counts

Projects are versioned as `Проверка исходных записей v1`, `Виды активности v1`,
`Аллюр v1`, and `Хромота v1`. The source-review project gets every verified
recording (including BLE-only sessions); the three `TimelineLabels` projects get
only recordings with MP4. Classes come from `guide/Woona_Виды_активности_чеклист.pdf`,
`guide/Woona_Аллюр_чеклист.pdf`, and `guide/Woona_Хромота_чеклист.pdf` (24 July
2026), interpreted under the corrected 30 July recording protocol. Label only
clean video intervals, mark rejected intervals as `Брак`, and record pass/jump
details in the per-region notes. Activity classes are `Лежит, не спит`, `Спит`,
`Ходит`, `Бегает`, `Прыгает`; gait classes are `Медленный шаг`, `Быстрый шаг без
перехода на рысь`, `Рысь`, `Галоп`; lameness-project intervals are `Спокойная
стойка`, `Обычный шаг`, `Лёгкая рысь`, `Бордюр`, `Лестница`. The separate clinical
lameness choice is optional and must come from verified clinical data, never be
inferred from video alone. The player is 400 px high. The timeline uses the
nominal 30 FPS of the source MP4s; because
phone video can be variable-frame-rate, frame-to-sensor alignment must use the
original sync metadata and timestamps rather than assuming exact 30 FPS. The project cards and project
API show `task_number` and `finished_task_number` separately for each
category. Task metadata retains its Woona recording UUID or Drive file ID,
source, dog, date and schema version. Source files are linked read-only from
the task. Existing reference CSV annotations remain source evidence; they
are not silently promoted to new Label Studio labels.

The sync is idempotent by `data.source_id`. Check it on demand:

```bash
docker compose --env-file .env --env-file .env.label \
  -f compose.yaml -f compose.label.yaml exec -T label_sync \
  python -m server.label_sync --once
```

## Backups

`server/backup.label.sh` briefly stops ingest and labeling, then captures
both PostgreSQL databases, Woona storage and Label Studio media in one
timestamped, SHA-256-checked set under `/srv/woona/backups`. Copy each set
off-host. The immutable Drive snapshot is separate and must also have an
off-host copy, together with its manifest and checksums. A restore is complete
only when database counts, `/v1/integrity`, local-file reads and Label Studio
project counts all pass.
