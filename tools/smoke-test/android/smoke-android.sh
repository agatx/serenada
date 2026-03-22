#!/usr/bin/env bash
# Android smoke test leg — ADB deep link + uiautomator state polling
#
# Required env vars:
#   SMOKE_SERVER_URL  — Server URL (e.g. http://192.168.1.5)
#   SMOKE_ROOM_ID     — Room ID for initial join
#   SMOKE_BARRIER_DIR — Barrier directory for synchronization
#   SMOKE_ARTIFACTS_DIR — Directory for screenshots
#
# Optional:
#   ANDROID_SERIAL    — ADB serial (defaults to first device)
#   SMOKE_FLOW        — "pair" (default) or "group"
#   SMOKE_EXPECTED_PARTICIPANTS — required for group flow

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

PACKAGE="app.serenada.android"
SERVER_URL="${SMOKE_SERVER_URL:?}"
ROOM_ID="${SMOKE_ROOM_ID:?}"
BARRIER_DIR="${SMOKE_BARRIER_DIR:?}"
ARTIFACTS_DIR="${SMOKE_ARTIFACTS_DIR:-$SCRIPT_DIR/../artifacts}"
SMOKE_FLOW="${SMOKE_FLOW:-pair}"
SMOKE_EXPECTED_PARTICIPANTS="${SMOKE_EXPECTED_PARTICIPANTS:-0}"

adb_cmd() {
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        adb -s "$ANDROID_SERIAL" "$@"
    else
        adb "$@"
    fi
}

clear_logcat() {
    adb_cmd logcat -c 2>/dev/null || true
}

# Dump UI hierarchy and return XML
ui_dump() {
    adb_cmd shell uiautomator dump /sdcard/smoke_dump.xml 2>/dev/null || true
    adb_cmd shell cat /sdcard/smoke_dump.xml 2>/dev/null || echo ""
}

# Wait for a testTag to appear in the UI hierarchy
wait_for_element() {
    local tag="$1" timeout="${2:-30}"
    local elapsed=0
    log_info "Android: waiting for element '$tag' (${timeout}s timeout) ..."
    while true; do
        local xml
        xml=$(ui_dump)
        if echo "$xml" | grep -q "$tag"; then
            log_ok "Android: found '$tag'"
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$timeout" ]; then
            log_error "Android: element '$tag' not found after ${timeout}s"
            take_screenshot "timeout_${tag}"
            return 1
        fi
    done
}

element_exists() {
    local tag="$1"
    local xml
    xml=$(ui_dump)
    echo "$xml" | grep -q "$tag"
}

element_value() {
    local tag="$1"
    local xml node value
    xml=$(ui_dump)
    # XML is a single line — isolate the specific node containing the tag
    node=$(echo "$xml" | grep -o "<node [^>]*${tag}[^>]*>" | head -1 || true)
    if [ -z "$node" ]; then
        return 1
    fi

    value=$(echo "$node" | sed -n 's/.*content-desc="\([^"]*\)".*/\1/p')
    if [ -z "$value" ]; then
        value=$(echo "$node" | sed -n 's/.*text="\([^"]*\)".*/\1/p')
    fi
    if [ -n "$value" ]; then
        echo "$value"
    fi
}

wait_for_participant_count() {
    local expected="$1" timeout="${2:-60}"
    local expected_remote_peers=$((expected - 1))
    local elapsed=0

    log_info "Android: waiting for participant count >= $expected (${timeout}s timeout) ..."
    while true; do
        local value
        value=$(element_value "call.participantCount" || true)
        if [[ "$value" =~ ^[0-9]+$ ]] && [ "$value" -ge "$expected" ]; then
            log_ok "Android: participant count reached $value"
            return 0
        fi

        local stats_lines
        stats_lines=$(adb_cmd logcat -d 2>/dev/null | grep '\[WebRTCStats\]' | tail -20 || true)
        if [ -n "$stats_lines" ]; then
            local remote_count
            remote_count=$(printf '%s\n' "$stats_lines" | sed -n 's/.*remote=\([^ ,]*\).*/\1/p' | sort -u | wc -l | tr -d ' ')
            if [ "$remote_count" -ge "$expected_remote_peers" ]; then
                log_ok "Android: WebRTC stats observed $remote_count distinct remote peers"
                return 0
            fi
        fi

        sleep 1
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$timeout" ]; then
            log_error "Android: participant count did not reach $expected after ${timeout}s (last='$value')"
            take_screenshot "timeout_participant_count_${expected}"
            return 1
        fi
    done
}

# Tap an element by its testTag — finds bounds and computes center
tap_element() {
    local tag="$1"
    local xml
    xml=$(ui_dump)

    # Find node with matching resource-id or content-desc containing the tag
    local bounds
    bounds=$(echo "$xml" | grep -o "resource-id=\"[^\"]*${tag}[^\"]*\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' || true)

    # Fallback: search for the tag string anywhere in the node attributes
    if [ -z "$bounds" ]; then
        bounds=$(echo "$xml" | grep "$tag" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 || true)
    fi

    if [ -z "$bounds" ]; then
        log_error "Android: could not find bounds for '$tag'"
        return 1
    fi

    # Parse bounds="[x1,y1][x2,y2]"
    # Replace ][ with space first, then strip remaining brackets
    local coords
    coords=$(echo "$bounds" | grep -o '\[.*\]' | sed 's/\]\[/ /g' | tr -d '[]"' | tr ',' ' ')
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<< "$(echo "$coords" | awk '{print $1, $2, $3, $4}')"

    local cx=$(( (x1 + x2) / 2 ))
    local cy=$(( (y1 + y2) / 2 ))

    log_info "Android: tapping '$tag' at ($cx, $cy)"
    adb_cmd shell input tap "$cx" "$cy"
}

take_screenshot() {
    local name="$1"
    mkdir -p "$ARTIFACTS_DIR"
    adb_cmd shell screencap -p /sdcard/smoke_screenshot.png 2>/dev/null || true
    adb_cmd pull /sdcard/smoke_screenshot.png "$ARTIFACTS_DIR/android_${name}.png" 2>/dev/null || true
}

tap_end_call_with_controls() {
    local timeout="${1:-12}"
    local elapsed=0

    log_info "Android: revealing call controls before tapping end call ..."
    while [ "$elapsed" -lt "$timeout" ]; do
        # Call controls auto-hide; tap center of call screen to reveal.
        tap_element "call.screen" || true
        sleep 1
        if element_exists "call.endCall"; then
            tap_element "call.endCall"
            return 0
        fi
        elapsed=$((elapsed + 1))
    done

    log_error "Android: end-call control did not appear after ${timeout}s"
    take_screenshot "missing_end_call_controls"
    return 1
}

# Pre-grant permissions
pre_grant_permissions() {
    log_info "Android: granting camera and microphone permissions ..."
    adb_cmd shell pm grant "$PACKAGE" android.permission.CAMERA 2>/dev/null || true
    adb_cmd shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO 2>/dev/null || true
}

launch_deep_link() {
    local room_id="$1"
    local url="${SERVER_URL}/call/${room_id}"
    log_info "Android: launching deep link $url"
    adb_cmd shell am start -a android.intent.action.VIEW -d "$url" 2>/dev/null
}

# --- Main flow ---

log_info "=== Android Smoke Test ==="

pre_grant_permissions
clear_logcat

if [ "$SMOKE_FLOW" = "group" ]; then
    if ! [[ "$SMOKE_EXPECTED_PARTICIPANTS" =~ ^[0-9]+$ ]] || [ "$SMOKE_EXPECTED_PARTICIPANTS" -lt 2 ]; then
        log_error "Android: SMOKE_EXPECTED_PARTICIPANTS must be >= 2 for group flow"
        exit 1
    fi

    launch_deep_link "$ROOM_ID"
    wait_for_element "call.screen" 30
    barrier_write "$BARRIER_DIR" "android.joined"

    wait_for_participant_count "$SMOKE_EXPECTED_PARTICIPANTS" 75
    take_screenshot "group_in_call"
    barrier_write "$BARRIER_DIR" "android.participant-count-ok" "$SMOKE_EXPECTED_PARTICIPANTS"

    barrier_wait "$BARRIER_DIR" "end" 45
    tap_end_call_with_controls 12
    wait_for_element "join.screen" 20
    barrier_write "$BARRIER_DIR" "android.done"
else
    # Phase 1: Join
    launch_deep_link "$ROOM_ID"
    wait_for_element "call.screen" 30
    barrier_write "$BARRIER_DIR" "android.joined"

    # Wait for peer
    barrier_wait "$BARRIER_DIR" "peer.ready" 45

    # Stabilize — brief pause for media connection
    sleep 1
    take_screenshot "in_call_1"
    barrier_write "$BARRIER_DIR" "android.in-call"

    # Phase 2: Leave
    barrier_wait "$BARRIER_DIR" "leave" 30
    tap_end_call_with_controls 12
    wait_for_element "join.screen" 20
    barrier_write "$BARRIER_DIR" "android.left"

    # Phase 3: Rejoin
    REJOIN_ROOM_ID=$(barrier_wait "$BARRIER_DIR" "rejoin" 30)
    if [ -z "$REJOIN_ROOM_ID" ]; then
        REJOIN_ROOM_ID="$ROOM_ID"
    fi

    launch_deep_link "$REJOIN_ROOM_ID"
    wait_for_element "call.screen" 30
    barrier_write "$BARRIER_DIR" "android.rejoined"

    # Wait for peer again
    barrier_wait "$BARRIER_DIR" "peer.ready.2" 45
    sleep 1
    take_screenshot "in_call_2"
    barrier_write "$BARRIER_DIR" "android.rejoin-in-call"

    # Phase 4: End
    barrier_wait "$BARRIER_DIR" "end" 30
    tap_end_call_with_controls 12
    wait_for_element "join.screen" 20
    barrier_write "$BARRIER_DIR" "android.done"
fi

log_ok "=== Android Smoke Test Complete ==="
