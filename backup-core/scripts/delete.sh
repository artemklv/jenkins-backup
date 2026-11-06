#!/usr/bin/env bash
# Delete one backup archive and its checksum file.
#
# Usage: delete.sh <backup file name>
#
# Environment:
#   BACKUP_DIR  Directory with backups (default: /var/backups/mongodb)
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [delete] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "Usage: $0 <backup file name>"

BACKUP_DIR="${BACKUP_DIR:-/var/backups/mongodb}"
name="$1"

# Plain file name only: no paths, only the archive extension
[[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.archive\.gz$ ]] || die "Invalid backup file name: '${name}'"
[[ -f "${BACKUP_DIR}/${name}" ]] || die "Backup not found: ${BACKUP_DIR}/${name}"

rm -f -- "${BACKUP_DIR}/${name}" "${BACKUP_DIR}/${name}.sha256"
log "Deleted ${BACKUP_DIR}/${name} (and its .sha256)"
