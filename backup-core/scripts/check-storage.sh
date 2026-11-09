#!/usr/bin/env bash
# Check the backup storage before a backup action.
#
#   1. BACKUP_DIR exists and is writable.
#   2. BACKUP_MOUNT is a mount point and BACKUP_DIR is on it, so backups never land
#      on the local disk when the network disk is not mounted.
#   3. At least MIN_FREE_SPACE_GB is free in BACKUP_DIR (used before a dump).
#
# Environment:
#   BACKUP_DIR          Backup directory (default: /var/backups/mongodb)
#   BACKUP_MOUNT        Mount point of the backup disk; empty skips check 2
#   MIN_FREE_SPACE_GB   Required free space in GB; empty or 0 skips check 3
#   STORAGE_RESPONSE_TIMEOUT  Seconds to wait for the backup disk to answer (default: 30)
set -euo pipefail

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [check-storage] $*"; }
die() { log "ERROR: $*"; exit 1; }

BACKUP_DIR="${BACKUP_DIR:-/var/backups/mongodb}"
BACKUP_MOUNT="${BACKUP_MOUNT:-}"
MIN_FREE_SPACE_GB="${MIN_FREE_SPACE_GB:-0}"
# Seconds to wait for the backup disk to answer
STORAGE_RESPONSE_TIMEOUT="${STORAGE_RESPONSE_TIMEOUT:-30}"

[[ "$MIN_FREE_SPACE_GB" =~ ^[0-9]+$ ]] \
  || die "MIN_FREE_SPACE_GB must be a non-negative integer, got '${MIN_FREE_SPACE_GB}'"

if [[ -n "$BACKUP_MOUNT" ]]; then
  # An unreachable NFS server makes file calls hang for minutes (soft mount
  # timeouts); fail fast with a clear message instead
  timeout "$STORAGE_RESPONSE_TIMEOUT" stat -f "$BACKUP_MOUNT" > /dev/null 2>&1 \
    || die "Backup disk does not respond within ${STORAGE_RESPONSE_TIMEOUT}s: ${BACKUP_MOUNT} (is the storage server up and reachable?)"
  mountpoint -q "$BACKUP_MOUNT" \
    || die "Backup disk is not mounted: ${BACKUP_MOUNT} is not a mount point"
  case "$BACKUP_DIR/" in
    "${BACKUP_MOUNT%/}/"*) ;;
    *) die "BACKUP_DIR ${BACKUP_DIR} is not on the backup disk ${BACKUP_MOUNT}" ;;
  esac
  log "Backup disk mounted: ${BACKUP_MOUNT} ($(findmnt -n -o FSTYPE,SOURCE --target "$BACKUP_MOUNT" 2>/dev/null || echo 'type unknown'))"
fi

[[ -d "$BACKUP_DIR" ]] || die "Backup directory not found: ${BACKUP_DIR}"
[[ -w "$BACKUP_DIR" ]] || die "Backup directory is not writable: ${BACKUP_DIR}"

# Free space in GB (df -P: 1024-byte blocks, 4th column = available)
free_kb="$(df -Pk "$BACKUP_DIR" | awk 'NR == 2 { print $4 }')"
free_gb=$((free_kb / 1024 / 1024))
log "Free space in ${BACKUP_DIR}: ${free_gb} GB"

if ((MIN_FREE_SPACE_GB > 0)) && ((free_gb < MIN_FREE_SPACE_GB)); then
  die "Not enough free space in ${BACKUP_DIR}: ${free_gb} GB, required ${MIN_FREE_SPACE_GB} GB (MIN_FREE_SPACE_GB)"
fi
