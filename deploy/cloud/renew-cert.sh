#!/bin/bash
set -euo pipefail
# Wait for DNS cutover before attempting HTTP-01 validation.
if ! getent ahostsv4 crm.emie.cn | awk '{print $1}' | sort -u | grep -qx '8.135.51.39'; then
  echo 'CRM DNS cutover pending; existing certificate remains active.'
  exit 0
fi
certbot certonly --config-dir /opt/emiecrm/letsencrypt/config --work-dir /opt/emiecrm/letsencrypt/work --logs-dir /opt/emiecrm/letsencrypt/logs --webroot -w /var/www/emiecrm-acme --cert-name crm.emie.cn -d crm.emie.cn --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring --deploy-hook /opt/emiecrm/install-cert.sh
