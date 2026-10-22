#!/bin/sh
set -eu
if [ "${1:-}" != --remove-service ]; then
  echo 'Usage: ./uninstall.sh --remove-service'
  echo 'Removes gateway resources only. Project data/backups remain.'
  exit 1
fi
cd /opt/emie-jst
docker compose down
if [ -f /opt/1panel/www/conf.d/emie-jst.conf ]; then
  mv /opt/1panel/www/conf.d/emie-jst.conf /opt/emie-jst/removed-nginx.conf
  if ! docker exec 1Panel-openresty-lgns nginx -t; then
    mv /opt/emie-jst/removed-nginx.conf /opt/1panel/www/conf.d/emie-jst.conf
    exit 1
  fi
  docker exec 1Panel-openresty-lgns nginx -s reload
fi
rm -f /etc/letsencrypt/renewal-hooks/deploy/emie-jst.sh
certbot delete --cert-name jstapi.emie.cn --non-interactive
rm -rf /opt/1panel/www/jstapi
for tag in emie-jst-gateway:0.1.0 emie-jst-gateway:0.2.0; do
  if docker image inspect "$tag" >/dev/null 2>&1; then docker image rm "$tag"; fi
done
echo 'Dedicated gateway removed. Data/backups remain in /opt/emie-jst.'
echo 'After copying and verifying backups elsewhere, delete that directory separately.'
