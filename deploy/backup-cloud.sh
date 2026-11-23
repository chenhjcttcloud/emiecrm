#!/bin/bash
set -euo pipefail
umask 077
cd /opt/emiecrm
backup="backups/daily/emiecrm_prod-$(TZ=Asia/Shanghai date +%Y%m%d-%H%M%S).sql.gz"
mkdir -p backups/daily
trap 'rm -f "$backup.partial"' EXIT
docker exec emiecrm-mysql-prod sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --hex-blob --routines --triggers emiecrm_prod' | gzip > "$backup.partial"
gzip -t "$backup.partial"
mv "$backup.partial" "$backup"
sha256sum "$backup" > "$backup.sha256"
echo "CRM backup complete: $backup"
