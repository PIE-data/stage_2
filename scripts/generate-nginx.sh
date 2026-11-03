#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_PATH="${1:-$PROJECT_ROOT/config/deployment.env}"

if [[ ! -f "$CONFIG_PATH" ]]; then
    echo "Configuration file not found: $CONFIG_PATH" >&2
    exit 1
fi

# Load a trusted local Bash configuration.
source "$CONFIG_PATH"

positive_integer() {
    local name="$1"
    local value="${!name-}"
    if [[ ! "$value" =~ ^[1-9][0-9]{0,8}$ ]]; then
        echo "$name must be a positive integer (at most 9 digits)" >&2
        exit 1
    fi
}

for name in SEARCH_REPLICAS SEARCH_BASE_PORT PORT_STEP GATEWAY_PORT \
    NGINX_WORKERS NGINX_WORKER_CONNECTIONS \
    PROXY_CONNECT_TIMEOUT_SECONDS PROXY_READ_TIMEOUT_SECONDS \
    UPSTREAM_FAIL_TIMEOUT_SECONDS; do
    positive_integer "$name"
done

last_search_port=$((SEARCH_BASE_PORT + (SEARCH_REPLICAS - 1) * PORT_STEP))

if (( GATEWAY_PORT > 65535 || last_search_port > 65535 )); then
    echo "Port numbers must not exceed 65535" >&2
    exit 1
fi

for ((i = 0; i < SEARCH_REPLICAS; i++)); do
    port=$((SEARCH_BASE_PORT + i * PORT_STEP))
    if (( port == GATEWAY_PORT )); then
        echo "Gateway port conflicts with a Search replica: $port" >&2
        exit 1
    fi
done

# Support explicit IPv4 addresses for the gateway listener.
if [[ ! "${GATEWAY_BIND:-}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    echo "GATEWAY_BIND must be an IPv4 address" >&2
    exit 1
fi

IFS='.' read -r -a octets <<< "$GATEWAY_BIND"
for octet in "${octets[@]}"; do
    if (( 10#$octet > 255 )); then
        echo "Invalid IPv4 address: $GATEWAY_BIND" >&2
        exit 1
    fi
done

: "${RUN_DIR:?RUN_DIR is required}"
: "${NGINX_BIN:?NGINX_BIN is required}"

if [[ "$RUN_DIR" != /* ]]; then
    echo "RUN_DIR must be an absolute Linux path" >&2
    exit 1
fi

command -v "$NGINX_BIN" >/dev/null || {
    echo "nginx executable not found: $NGINX_BIN" >&2
    exit 1
}

GATEWAY_DIR="$RUN_DIR/nginx"
mkdir -p "$GATEWAY_DIR/logs"

temporary="$(mktemp "$GATEWAY_DIR/nginx.conf.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT

{
    cat <<HEADER
worker_processes $NGINX_WORKERS;
pid logs/nginx.pid;
error_log logs/error.log info;

events {
    worker_connections $NGINX_WORKER_CONNECTIONS;
}

http {
HEADER

    cat <<'STATIC'
    log_format gateway '$time_iso8601 "$request" status=$status '
                       'upstream=$upstream_addr upstream_status=$upstream_status '
                       'request_time=$request_time';

    access_log logs/access.log gateway;
    client_body_temp_path client_temp;
    proxy_temp_path proxy_temp;

    upstream search_replicas {
STATIC

    for ((i = 0; i < SEARCH_REPLICAS; i++)); do
        port=$((SEARCH_BASE_PORT + i * PORT_STEP))
        printf '        server 127.0.0.1:%s max_fails=1 fail_timeout=%ss;\n' \
            "$port" "$UPSTREAM_FAIL_TIMEOUT_SECONDS"
    done

    cat <<SERVER
    }

    server {
        listen $GATEWAY_BIND:$GATEWAY_PORT;
        server_name localhost;

        location / {
            proxy_pass http://search_replicas;
            proxy_connect_timeout ${PROXY_CONNECT_TIMEOUT_SECONDS}s;
            proxy_read_timeout ${PROXY_READ_TIMEOUT_SECONDS}s;
            proxy_next_upstream error timeout;
            proxy_next_upstream_tries $SEARCH_REPLICAS;
SERVER

    cat <<'STATIC'
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            add_header X-Search-Upstream $upstream_addr always;
        }
    }
}
STATIC
} > "$temporary"

"$NGINX_BIN" -p "$GATEWAY_DIR/" -c "$temporary" -t
mv -- "$temporary" "$GATEWAY_DIR/nginx.conf"

printf 'Generated configuration: %s\n' "$GATEWAY_DIR/nginx.conf"
printf 'Search replicas: %s\n' "$SEARCH_REPLICAS"
printf 'Gateway: %s:%s\n' "$GATEWAY_BIND" "$GATEWAY_PORT"
