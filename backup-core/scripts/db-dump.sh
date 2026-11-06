#!/usr/bin/env bash
# Create a gzip-compressed MongoDB archive and its SHA256 checksum.
#
# Output: ${BACKUP_DIR}/<MONGO_DB>_<YYYYmmdd_HHMMSS>.archive.gz (+ .sha256)
#
# Environment:
#   MONGO_HOST         MongoDB host (default: localhost)
#   MONGO_PORT         MongoDB port (default: 27017)
#   MONGO_DB           Database to dump (required)
#   MONGO_AUTH_SOURCE  Authentication database (default: admin)
#   MONGO_USER         MongoDB user (optional, no auth when empty)
#   MONGO_PASSWORD     MongoDB password
#   BACKUP_DIR         Target directory (default: /var/backups/mongodb)
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [db-dump] $*"; }

MONGO_HOST="${MONGO_HOST:-localhost}"
MONGO_PORT="${MONGO_PORT:-27017}"
MONGO_DB="${MONGO_DB:?MONGO_DB is required}"
MONGO_AUTH_SOURCE="${MONGO_AUTH_SOURCE:-admin}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/mongodb}"

mkdir -p "$BACKUP_DIR"

name="${MONGO_DB}_$(date '+%Y%m%d_%H%M%S').archive.gz"
target="${BACKUP_DIR}/${name}"
# Write to a temporary name first so a partial dump never looks like a backup
partial="${target}.tmp"

# Pass the password through a private config file instead of the command line
config="$(mktemp)"
chmod 600 "$config"
trap 'rm -f "$config" "$partial"' EXIT

auth_args=()
if [[ -n "${MONGO_USER:-}" ]]; then
  # YAML single-quoted string: escape ' as ''
  pw="${MONGO_PASSWORD:-}"
  printf "password: '%s'\n" "${pw//\'/\'\'}" > "$config"
  auth_args=(--username "$MONGO_USER" --authenticationDatabase "$MONGO_AUTH_SOURCE" --config "$config")
fi

log "Dumping database '${MONGO_DB}' from ${MONGO_HOST}:${MONGO_PORT}"
mongodump \
  --host "$MONGO_HOST" --port "$MONGO_PORT" \
  "${auth_args[@]}" \
  --db "$MONGO_DB" \
  --gzip --archive="$partial"

mv "$partial" "$target"

# Checksum file uses the bare file name, so 'sha256sum -c' works inside BACKUP_DIR
(cd "$BACKUP_DIR" && sha256sum "$name" > "${name}.sha256")

size="$(du -h "$target" | cut -f1)"
log "Backup created: ${target} (${size})"
log "Checksum: $(cut -d' ' -f1 "${target}.sha256")"
