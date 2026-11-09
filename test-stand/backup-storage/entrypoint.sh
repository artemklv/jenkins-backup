#!/bin/sh
# Start the kernel NFS server (NFSv4 only, TCP 2049) and export /exports.
#
# Environment:
#   NFS_CLIENTS    Allowed clients, e.g. a subnet (default: *)
#   NFS_ANON_UID   Owner of all files written over NFS (default: 1000, the jenkins user)
#   NFS_ANON_GID   Group of all files written over NFS (default: 1000)
#   NFS_THREADS    Number of nfsd threads (default: 8)
set -eu

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [nfs-server] $*"; }

NFS_CLIENTS="${NFS_CLIENTS:-*}"
NFS_ANON_UID="${NFS_ANON_UID:-1000}"
NFS_ANON_GID="${NFS_ANON_GID:-1000}"
NFS_THREADS="${NFS_THREADS:-8}"

mkdir -p /exports
chown "${NFS_ANON_UID}:${NFS_ANON_GID}" /exports

# all_squash: every client user (root included) writes as NFS_ANON_UID:NFS_ANON_GID,
# so file ownership does not depend on the uid of the mounting process
echo "/exports ${NFS_CLIENTS}(rw,sync,no_subtree_check,fsid=0,all_squash,anonuid=${NFS_ANON_UID},anongid=${NFS_ANON_GID})" \
  > /etc/exports

mountpoint -q /proc/fs/nfsd || mount -t nfsd nfsd /proc/fs/nfsd

shutdown() {
  log "Stopping"
  rpc.nfsd 0 || true
  exportfs -ua || true
  exit 0
}
trap shutdown TERM INT

exportfs -ra
# NFSv4 only: no rpcbind, no NFSv3 side protocols
rpc.mountd -N 3 -V 4
rpc.nfsd -N 3 -V 4 "$NFS_THREADS"

log "Exporting /exports to ${NFS_CLIENTS}"
exportfs -v

# Keep the container alive; 'wait' lets the trap run on SIGTERM
while :; do
  sleep 3600 &
  wait $!
done
