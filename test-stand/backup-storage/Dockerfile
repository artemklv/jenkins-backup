# Backup storage node: holds backups on the shared disk and exposes
# a read-only HTTP listing inside the Docker network.
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf

# Mount point of the shared backup disk
RUN mkdir -p /srv/backups

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1/health || exit 1
