#!/usr/bin/env bash
# Room creation via /api/room-id

set -euo pipefail

_ROOM_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$_ROOM_SH_DIR/common.sh"

# Create a new room and return the room ID
create_room() {
    local server_url="$1"
    local response
    response=$(curl -sf -X POST "$server_url/api/room-id" 2>/dev/null) || {
        log_error "Failed to create room via $server_url/api/room-id"
        return 1
    }

    # Response is JSON: {"roomId":"..."}
    local room_id
    room_id=$(echo "$response" | grep -o '"roomId":"[^"]*"' | cut -d'"' -f4)
    if [ -z "$room_id" ]; then
        log_error "Could not parse room ID from response: $response"
        return 1
    fi

    echo "$room_id"
}
