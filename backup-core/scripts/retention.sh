#!/usr/bin/env bash
# Delete backups older than RETENTION_DAYS together with their checksum files.
# The most recent backup is always kept, even if it is older than the limit.
#
# Environment:
#   BACKUP_DIR      Directory with backups (default: /var/backups/mongodb)
#   RETENTION_DAYS  Keep backups modified within this many days (default: 7)
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [retention] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

BACKUP_DIR="${BACKUP_DIR:-/var/backups/mongodb}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

[[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] || die "RETENTION_DAYS must be a non-negative integer, got '${RETENTION_DAYS}'"
[[ -d "$BACKUP_DIR" ]] || die "Backup directory not found: ${BACKUP_DIR}"

log "Removing backups older than ${RETENTION_DAYS} day(s) from ${BACKUP_DIR}"

newest="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.archive.gz' -printf '%T@ %f\n' \
  | sort -rn | head -n1 | cut -d' ' -f2-)"

removed=0
while IFS= read -r -d '' file; do
  name="$(basename "$file")"
  if [[ "$name" == "$newest" ]]; then
    log "Keeping ${name}: it is the most recent backup"
    continue
  fi
  rm -f -- "$file" "${file}.sha256"
  log "Removed ${name}"
  removed=$((removed + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.archive.gz' -mmin "+$((RETENTION_DAYS * 1440))" -print0)

# Checksum files whose archive no longer exists
while IFS= read -r -d '' sum; do
  if [[ ! -f "${sum%.sha256}" ]]; then
    rm -f -- "$sum"
    log "Removed orphan checksum $(basename "$sum")"
  fi
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.archive.gz.sha256' -print0)

log "Rotation finished: ${removed} backup(s) removed"
