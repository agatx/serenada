#!/usr/bin/env bash
# Docker server start/stop/health-check

set -euo pipefail

_SERVER_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$_SERVER_SH_DIR/common.sh"

server_health_check() {
    local url="$1" timeout="${2:-30}"
    local elapsed=0
    log_info "Health-checking server at $url ..."
    while true; do
        if curl -sf -X POST "$url/api/room-id" -o /dev/null 2>/dev/null; then
            log_ok "Server is healthy"
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$timeout" ]; then
            log_error "Server health check timed out after ${timeout}s"
            return 1
        fi
    done
}

server_start() {
    local repo_root="$1" lan_ip="${2:-}"
    log_info "Building web client ..."
    (cd "$repo_root/client" && npm run build) || {
        log_error "Web client build failed"
        return 1
    }

    # Append LAN origin to ALLOWED_ORIGINS if needed
    if [ -n "$lan_ip" ]; then
        local env_file="$repo_root/.env"
        if [ -f "$env_file" ]; then
            local lan_origin="http://$lan_ip"
            if ! grep -q "$lan_origin" "$env_file" 2>/dev/null; then
                log_info "Appending $lan_origin to ALLOWED_ORIGINS in .env"
                if grep -q '^ALLOWED_ORIGINS=' "$env_file"; then
                    sed -i '' "s|^ALLOWED_ORIGINS=\(.*\)|ALLOWED_ORIGINS=\1,$lan_origin|" "$env_file"
                else
                    echo "ALLOWED_ORIGINS=$lan_origin" >> "$env_file"
                fi
            fi
        fi
    fi

    log_info "Starting Docker stack ..."
    (cd "$repo_root" && docker compose up -d --build) || {
        log_error "Docker compose up failed"
        return 1
    }

    server_health_check "http://localhost" 60
}

server_stop() {
    local repo_root="$1"
    log_info "Stopping Docker stack ..."
    (cd "$repo_root" && docker compose down) || true
}
