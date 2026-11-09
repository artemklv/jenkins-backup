# Backup storage node: an NFS server that exports the backup disk.
# Jenkins mounts the export over the network (NFSv4.1); there is no HTTP here.
# Needs a privileged container: the kernel NFS server (nfsd) runs in the host kernel.
FROM alpine:3.20

RUN apk add --no-cache nfs-utils

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

# The exported directory must be a real filesystem (a volume), not the image overlay
VOLUME /exports

EXPOSE 2049

# Healthy when nfsd runs with at least one thread
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD test "$(cat /proc/fs/nfsd/threads 2>/dev/null || echo 0)" -gt 0 || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
