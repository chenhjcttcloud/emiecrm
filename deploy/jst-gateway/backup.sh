#!/bin/bash
set -euo pipefail
umask 077
cd /opt/emie-jst
stamp=$(date -u +%Y%m%dT%H%M%SZ)
dest="/opt/emie-jst/backups/$stamp"
mkdir -p "$dest"
export COMPOSE_ANSI=never
running_image=$(docker inspect --format '{{.Image}}' "$(docker compose ps -q gateway)")
docker compose stop gateway
trap 'docker compose start gateway >/dev/null' EXIT HUP INT TERM
tar --exclude=emie-jst/backups --exclude=emie-jst/acme -czf "$dest/project.tar.gz" -C /opt emie-jst
docker image save "$running_image" | gzip > "$dest/image.tar.gz"
cp /opt/1panel/www/conf.d/emie-jst.conf "$dest/nginx.conf"
cp /etc/letsencrypt/renewal-hooks/deploy/emie-jst.sh "$dest/renew-hook.sh"
(cd "$dest" && sha256sum project.tar.gz image.tar.gz nginx.conf renew-hook.sh > SHA256SUMS)
echo "Backup saved: $dest (contains secrets; keep private)"
