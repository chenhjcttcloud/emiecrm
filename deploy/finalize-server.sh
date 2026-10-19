#!/bin/bash
# Existing 1Panel database only; never imports or replaces business data.
set -euo pipefail
cd /home/emie/emiecrm
docker compose -p emiecrm -f compose.production.yaml config --quiet
docker compose -p emiecrm -f compose.production.yaml up -d --no-build --no-deps crm
curl --retry 20 --retry-connrefused --retry-delay 2 -fsS http://127.0.0.1:8088/api/status
