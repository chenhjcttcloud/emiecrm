#!/bin/bash
set -euo pipefail
install -m 644 /opt/emiecrm/letsencrypt/config/live/crm.emie.cn/fullchain.pem /opt/emiecrm/fullchain.pem
install -m 600 /opt/emiecrm/letsencrypt/config/live/crm.emie.cn/privkey.pem /opt/emiecrm/privkey.pem
nginx -t
systemctl reload nginx
