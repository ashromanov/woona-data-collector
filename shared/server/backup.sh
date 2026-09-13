#!/usr/bin/env bash
set -euo pipefail
umask 077

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backup_root="${WOONA_BACKUP_ROOT:-/srv/woona/backups}"
storage_root="${WOONA_STORAGE_ROOT_HOST:-/srv/woona/storage}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
database_target="$backup_root/postgres-$timestamp.dump"
storage_target="$backup_root/storage-$timestamp.tar"
checksum_target="$backup_root/backup-$timestamp.sha256"
compose=(docker compose --env-file .env -f compose.yaml -f compose.production.yaml)
api_was_running=false

cleanup() {
    status=$?
    trap - EXIT
    rm -f -- "$database_target.tmp" "$storage_target.tmp" "$checksum_target.tmp"
    if $api_was_running; then
        "${compose[@]}" up -d --wait api >/dev/null
    fi
    exit "$status"
}
trap cleanup EXIT

mkdir -p "$backup_root"
test -d "$storage_root"
cd "$project_root"
if "${compose[@]}" ps --status running --services | grep -qx api; then
    api_was_running=true
    "${compose[@]}" stop -t 60 api >/dev/null
fi
"${compose[@]}" exec -T postgres pg_dump \
    --username="${POSTGRES_USER:-woona}" \
    --dbname="${POSTGRES_DB:-woona}" \
    --format=custom --no-owner --no-acl > "$database_target.tmp"
tar --create --file="$storage_target.tmp" \
    --exclude='./incoming' --exclude='./incoming/*' \
    --exclude='./backups' --exclude='./backups/*' \
    --exclude='*.tmp' --exclude='*.part' --exclude='./.health' \
    --directory="$storage_root" .
mv "$database_target.tmp" "$database_target"
mv "$storage_target.tmp" "$storage_target"
(
    cd "$backup_root"
    sha256sum "$(basename "$database_target")" "$(basename "$storage_target")" \
        > "$(basename "$checksum_target").tmp"
    mv "$(basename "$checksum_target").tmp" "$(basename "$checksum_target")"
)
find "$backup_root" -type f \( \
    -name 'postgres-*.dump' -o -name 'storage-*.tar' -o \
    -name 'backup-*.sha256' -o -name 'postgres-*.dump.sha256' \
    \) \
    -mtime +13 -delete
