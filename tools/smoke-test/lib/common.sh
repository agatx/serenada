#!/usr/bin/env bash
# Common utilities: logging, barrier helpers, LAN IP detection

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No color

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# Barrier helpers — file-based synchronization between test legs
barrier_write() {
    local dir="$1" name="$2"
    local content="${3:-}"
    if [ -n "$content" ]; then
        echo "$content" > "$dir/$name"
    else
        touch "$dir/$name"
    fi
}

barrier_wait() {
    local dir="$1" name="$2" timeout="${3:-30}"
    local start
    start=$(date +%s)
    while [ ! -f "$dir/$name" ]; do
        sleep 0.2
        local now
        now=$(date +%s)
        if [ $((now - start)) -ge "$timeout" ]; then
            log_error "Barrier timeout: waited ${timeout}s for '$name'"
            return 1
        fi
    done
    # If barrier file has content, print it
    local content
    content=$(cat "$dir/$name" 2>/dev/null || true)
    if [ -n "$content" ]; then
        echo "$content"
    fi
}

barrier_wait_all() {
    local dir="$1" timeout="$2"
    shift 2
    for name in "$@"; do
        barrier_wait "$dir" "$name" "$timeout" || return 1
    done
}

# Detect Mac LAN IP for mobile device access
detect_lan_ip() {
    local ip
    # Try en0 (Wi-Fi) first, then en1
    ip=$(ipconfig getifaddr en0 2>/dev/null || true)
    if [ -z "$ip" ]; then
        ip=$(ipconfig getifaddr en1 2>/dev/null || true)
    fi
    if [ -z "$ip" ]; then
        log_error "Could not detect LAN IP. Ensure Wi-Fi or Ethernet is connected."
        return 1
    fi
    echo "$ip"
}

# Get the repo root (parent of tools/)
repo_root() {
    local dir
    dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../" && pwd)"
    echo "$dir"
}
