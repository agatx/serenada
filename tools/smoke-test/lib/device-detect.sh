#!/usr/bin/env bash
# Android (adb) and iOS (xcrun devicectl) device detection

set -euo pipefail

_DEVICE_DETECT_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$_DEVICE_DETECT_SH_DIR/common.sh"

# Check if an Android device is connected via ADB
# Outputs serial to stdout; logs go to stderr
detect_android() {
    if ! command -v adb &>/dev/null; then
        log_warn "adb not found — skipping Android" >&2
        return 1
    fi

    local devices
    devices=$(adb devices 2>/dev/null | grep -v '^List' | grep -v '^$' | grep 'device$' || true)
    if [ -z "$devices" ]; then
        log_warn "No Android device connected" >&2
        return 1
    fi

    local serial
    serial=$(echo "$devices" | head -1 | awk '{print $1}')
    log_ok "Android device detected: $serial" >&2
    echo "$serial"
}

# Check if an iOS device is connected via xcrun devicectl
# Outputs UDID to stdout; logs go to stderr
detect_ios() {
    if ! command -v xcrun &>/dev/null; then
        log_warn "xcrun not found — skipping iOS" >&2
        return 1
    fi

    local udid
    # Try xcrun devicectl first (Xcode 15+)
    local devicectl_output
    devicectl_output=$(xcrun devicectl list devices 2>/dev/null || true)
    if echo "$devicectl_output" | grep -q 'connected'; then
        udid=$(echo "$devicectl_output" \
            | grep 'connected' \
            | head -1 \
            | grep -oE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40}' \
            || true)
    fi

    # Fallback: try instruments/idevice_id
    if [ -z "${udid:-}" ]; then
        udid=$(xcrun xctrace list devices 2>/dev/null \
            | grep -v 'Simulator' \
            | grep -oE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40}' \
            | head -1 \
            || true)
    fi

    if [ -z "${udid:-}" ]; then
        log_warn "No iOS device connected" >&2
        return 1
    fi

    log_ok "iOS device detected: $udid" >&2
    echo "$udid"
}
