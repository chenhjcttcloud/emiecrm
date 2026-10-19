#!/bin/bash
set -euo pipefail
umask 077
cd /home/emie/emiecrm
mkdir -p backups/daily
backup_file="backups/daily/mysql-$(TZ=Asia/Shanghai date +%Y%m%d-%H%M%S).sql.gz"
trap 'rm -f "$backup_file.partial"' EXIT
docker exec "${CRM_MYSQL_CONTAINER:?Set CRM_MYSQL_CONTAINER in backup service environment}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF emiecrm' | gzip > "$backup_file.partial"
mv "$backup_file.partial" "$backup_file"
echo "Backup completed: $backup_file"
