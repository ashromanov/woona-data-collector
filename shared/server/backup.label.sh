#!/usr/bin/env bash
set -euo pipefail
umask 077

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
read_env() {
    python3 -c 'import sys; from pathlib import Path; print(next(line.split("=", 1)[1] for line in Path(sys.argv[1]).read_text().splitlines() if line.startswith(sys.argv[2] + "=")))' .env "$1"
}
POSTGRES_USER="$(read_env POSTGRES_USER)"
POSTGRES_DB="$(read_env POSTGRES_DB)"
WOONA_STORAGE_ROOT_HOST="$(read_env WOONA_STORAGE_ROOT_HOST)"

backup_root="${WOONA_BACKUP_ROOT:-/srv/woona/backups}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
compose=(docker compose --env-file .env --env-file .env.label -f compose.yaml -f compose.label.yaml)
stopped=false

restart() {
    status=$?
    trap - EXIT
    if $stopped; then
        "${compose[@]}" up -d --wait api label_studio label_sync >/dev/null
    fi
    exit "$status"
}
trap restart EXIT

mkdir -p "$backup_root"
test -d "$WOONA_STORAGE_ROOT_HOST"
stopped=true
"${compose[@]}" stop -t 60 label_sync api label_studio >/dev/null
"${compose[@]}" exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    --format=custom --no-owner --no-acl > "$backup_root/woona-$stamp.dump.tmp"
"${compose[@]}" exec -T label_postgres pg_dump -U labelstudio -d labelstudio \
    --format=custom --no-owner --no-acl > "$backup_root/labels-$stamp.dump.tmp"
tar -cf "$backup_root/storage-$stamp.tar.tmp" \
    --exclude='./incoming' --exclude='./incoming/*' --exclude='*.part' \
    -C "$WOONA_STORAGE_ROOT_HOST" .
docker cp shared-label_studio-1:/label-studio/data/. - > "$backup_root/label-media-$stamp.tar.tmp"
for name in woona.dump labels.dump storage.tar label-media.tar; do
    prefix="${name%%.*}"
    suffix="${name#*.}"
    mv "$backup_root/$prefix-$stamp.$suffix.tmp" "$backup_root/$prefix-$stamp.$suffix"
done
(
    cd "$backup_root"
    sha256sum "woona-$stamp.dump" "labels-$stamp.dump" \
        "storage-$stamp.tar" "label-media-$stamp.tar" > "backup-$stamp.sha256.tmp"
    mv "backup-$stamp.sha256.tmp" "backup-$stamp.sha256"
)
printf '%s\n' "$backup_root/backup-$stamp.sha256"
