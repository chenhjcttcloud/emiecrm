#!/bin/sh
set -eu

network=${1:-emiecrm-prod-network}
subnets=$(docker network inspect --format '{{if eq .Driver "bridge"}}{{range .IPAM.Config}}{{if .Subnet}}{{println .Subnet}}{{end}}{{end}}{{end}}' "$network")
if [ -z "$subnets" ]; then
    printf 'No subnet found for Docker bridge %s\n' "$network" >&2
    exit 1
fi

printf 'location ^~ /_internal/jst/ {\n'
printf '%s\n' "$subnets" | while IFS= read -r subnet; do
    printf '    allow %s;\n' "$subnet"
done
cat <<'NGINX'
    deny all;
    proxy_pass http://127.0.0.1:18766/;
    proxy_read_timeout 40s;
}
NGINX
