#!/usr/bin/env bash
# Verify a backup checksum and restore it into TARGET_DB.
#
# Usage: db-restore.sh <backup file name or path>
#
# Environment:
#   MONGO_HOST         MongoDB host (default: localhost)
#   MONGO_PORT         MongoDB port (default: 27017)
#   MONGO_AUTH_SOURCE  Authentication database (default: admin)
#   MONGO_USER         MongoDB user (optional, no auth when empty)
#   MONGO_PASSWORD     MongoDB password
#   BACKUP_DIR         Directory with backups (default: /var/backups/mongodb)
#   TARGET_DB          Database to restore into (required, dropped collections are replaced)
#   SOURCE_DB          Database name inside the archive (default: parsed from the
#                      file name '<db>_<YYYYmmdd_HHMMSS>.archive.gz', then MONGO_DB)
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [db-restore] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "Usage: $0 <backup file>"

MONGO_HOST="${MONGO_HOST:-localhost}"
MONGO_PORT="${MONGO_PORT:-27017}"
MONGO_AUTH_SOURCE="${MONGO_AUTH_SOURCE:-admin}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/mongodb}"
TARGET_DB="${TARGET_DB:?TARGET_DB is required}"

name="$(basename "$1")"
file="${BACKUP_DIR}/${name}"

[[ -f "$file" ]] || die "Backup not found: ${file}"
[[ -f "${file}.sha256" ]] || die "Checksum file not found: ${file}.sha256"

case "$TARGET_DB" in
  admin|local|config) die "Refusing to restore into system database '${TARGET_DB}'" ;;
esac

if [[ -z "${SOURCE_DB:-}" ]]; then
  if [[ "$name" =~ ^(.+)_[0-9]{8}_[0-9]{6}\.archive\.gz$ ]]; then
    SOURCE_DB="${BASH_REMATCH[1]}"
  else
    SOURCE_DB="${MONGO_DB:?Cannot detect the source database, set SOURCE_DB or MONGO_DB}"
  fi
fi

log "Verifying checksum of ${name}"
(cd "$BACKUP_DIR" && sha256sum -c "${name}.sha256") || die "Checksum verification failed"

# Pass the password through a private config file instead of the command line
config="$(mktemp)"
chmod 600 "$config"
trap 'rm -f "$config"' EXIT

auth_args=()
if [[ -n "${MONGO_USER:-}" ]]; then
  # YAML single-quoted string: escape ' as ''
  pw="${MONGO_PASSWORD:-}"
  printf "password: '%s'\n" "${pw//\'/\'\'}" > "$config"
  auth_args=(--username "$MONGO_USER" --authenticationDatabase "$MONGO_AUTH_SOURCE" --config "$config")
fi

log "Restoring '${SOURCE_DB}' -> '${TARGET_DB}' on ${MONGO_HOST}:${MONGO_PORT} (existing collections are dropped)"
mongorestore \
  --host "$MONGO_HOST" --port "$MONGO_PORT" \
  "${auth_args[@]}" \
  --gzip --archive="$file" \
  --nsInclude "${SOURCE_DB}.*" \
  --nsFrom "${SOURCE_DB}.*" --nsTo "${TARGET_DB}.*" \
  --drop

log "Restore completed into '${TARGET_DB}'"
