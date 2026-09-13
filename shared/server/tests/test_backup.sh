#!/usr/bin/env bash
set -euo pipefail

test_root="$(mktemp -d)"
trap 'rm -r -- "$test_root"' EXIT
mkdir -p \
    "$test_root/storage/recordings/aa" \
    "$test_root/storage/incoming" \
    "$test_root/storage/backups" \
    "$test_root/backups"
printf 'keep' > "$test_root/storage/recordings/aa/content.bin"
printf 'drop' > "$test_root/storage/incoming/upload.part"
printf 'drop' > "$test_root/storage/backups/nested.dump"
printf 'drop' > "$test_root/storage/.health"
printf 'drop' > "$test_root/storage/stale.tmp"

docker() {
    printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
    case "$*" in
        *"ps --status running --services"*) printf 'api\n' ;;
        *"exec -T postgres pg_dump"*) printf 'portable database dump\n' ;;
    esac
}
export -f docker
export FAKE_DOCKER_LOG="$test_root/docker.log"

WOONA_BACKUP_ROOT="$test_root/backups" \
WOONA_STORAGE_ROOT_HOST="$test_root/storage" \
    bash "$(dirname "$0")/../backup.sh"

checksum="$(find "$test_root/backups" -name 'backup-*.sha256' -print -quit)"
test -n "$checksum"
(cd "$test_root/backups" && sha256sum -c "$(basename "$checksum")")
archive="$(find "$test_root/backups" -name 'storage-*.tar' -print -quit)"
tar --list --file="$archive" | grep -qx './recordings/aa/content.bin'
if tar --list --file="$archive" \
    | grep -Eq 'incoming|backups|\.health|\.part|\.tmp'; then
    exit 1
fi
grep -q -- '--no-owner --no-acl' "$test_root/docker.log"
grep -q -- 'stop -t 60 api' "$test_root/docker.log"
grep -q -- 'up -d --wait api' "$test_root/docker.log"
