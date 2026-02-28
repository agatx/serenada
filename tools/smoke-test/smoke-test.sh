#!/usr/bin/env bash
# Cross-platform smoke test orchestrator
#
# Usage: bash tools/smoke-test/smoke-test.sh
#
# Environment variables:
#   SMOKE_SERVER        — Server URL override (empty = local Docker)
#   SMOKE_PAIRS         — Comma-separated test pairs (default: web+android,web+ios)
#   SMOKE_ARTIFACTS_DIR — Screenshots and logs dir (default: tools/smoke-test/artifacts)
#   SMOKE_SKIP_BUILD    — Skip platform builds (default: 0)
#   SMOKE_KEEP_SERVER   — Don't stop Docker on exit (default: 0)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"
source "$SCRIPT_DIR/lib/server.sh"
source "$SCRIPT_DIR/lib/room.sh"
source "$SCRIPT_DIR/lib/device-detect.sh"
source "$SCRIPT_DIR/lib/report.sh"

# --- Configuration ---
SMOKE_SERVER="${SMOKE_SERVER:-}"
SMOKE_PAIRS="${SMOKE_PAIRS:-web+android,web+ios}"
SMOKE_ARTIFACTS_DIR="${SMOKE_ARTIFACTS_DIR:-$SCRIPT_DIR/artifacts}"
SMOKE_SKIP_BUILD="${SMOKE_SKIP_BUILD:-0}"
SMOKE_KEEP_SERVER="${SMOKE_KEEP_SERVER:-0}"

REPO_ROOT="$(repo_root)"
LOCAL_SERVER=false
SERVER_URL=""
LAN_IP=""
WEB_DIR="$SCRIPT_DIR/web"

# Track background PIDs for cleanup
PIDS=()
BARRIER_DIRS=()

cleanup() {
    log_info "Cleaning up ..."
    for pid in ${PIDS[@]+"${PIDS[@]}"}; do
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    done
    for dir in ${BARRIER_DIRS[@]+"${BARRIER_DIRS[@]}"}; do
        rm -rf "$dir" 2>/dev/null || true
    done
    if [ "$LOCAL_SERVER" = true ] && [ "$SMOKE_KEEP_SERVER" != "1" ]; then
        server_stop "$REPO_ROOT"
    fi
}
trap cleanup EXIT

# --- Step 1: Source .env ---
if [ -f "$REPO_ROOT/.env" ]; then
    log_info "Sourcing .env from $REPO_ROOT"
    set -a
    source "$REPO_ROOT/.env"
    set +a
fi

# --- Step 2: Detect devices ---
log_info "Detecting devices ..."
ANDROID_SERIAL=""
IOS_UDID=""

ANDROID_AVAILABLE=false
IOS_AVAILABLE=false

if echo "$SMOKE_PAIRS" | grep -q "android"; then
    ANDROID_SERIAL=$(detect_android 2>/dev/null) && ANDROID_AVAILABLE=true || true
fi

if echo "$SMOKE_PAIRS" | grep -q "ios"; then
    IOS_UDID=$(detect_ios 2>/dev/null) && IOS_AVAILABLE=true || true
fi

# --- Step 3: Server setup ---
if [ -n "$SMOKE_SERVER" ]; then
    SERVER_URL="$SMOKE_SERVER"
    log_info "Using remote server: $SERVER_URL"
    server_health_check "$SERVER_URL" 15
else
    LOCAL_SERVER=true
    SERVER_URL="http://localhost"

    # Detect LAN IP for mobile devices
    if [ "$ANDROID_AVAILABLE" = true ] || [ "$IOS_AVAILABLE" = true ]; then
        LAN_IP=$(detect_lan_ip)
        log_info "LAN IP for mobile devices: $LAN_IP"
    fi

    if [ "$SMOKE_SKIP_BUILD" != "1" ]; then
        server_start "$REPO_ROOT" "$LAN_IP"
    else
        server_health_check "$SERVER_URL" 15
    fi
fi

# --- Step 3b: Mobile platform builds ---
if [ "$SMOKE_SKIP_BUILD" != "1" ]; then
    if [ "$ANDROID_AVAILABLE" = true ]; then
        log_info "Building and installing Android APK ..."
        (cd "$REPO_ROOT/client-android" && \
            ANDROID_SERIAL="$ANDROID_SERIAL" \
            ./gradlew :app:installDebug) || {
            log_error "Android build/install failed"
            exit 1
        }
    fi
fi

# --- Step 4: Resolve platform URLs ---
WEB_URL="$SERVER_URL"
MOBILE_URL="$SERVER_URL"
if [ "$LOCAL_SERVER" = true ] && [ -n "$LAN_IP" ]; then
    MOBILE_URL="http://$LAN_IP"
fi

# --- Step 5: Install Playwright ---
log_info "Installing Playwright dependencies ..."
(cd "$WEB_DIR" && npm install && npx playwright install chromium) || {
    log_error "Playwright install failed"
    exit 1
}

# --- Step 6: Run test pairs ---
mkdir -p "$SMOKE_ARTIFACTS_DIR"

IFS=',' read -ra PAIRS <<< "$SMOKE_PAIRS"

for pair in "${PAIRS[@]}"; do
    pair=$(echo "$pair" | tr -d ' ')
    log_info "=========================================="
    log_info "Running pair: $pair"
    log_info "=========================================="

    # Parse pair
    IFS='+' read -r PLATFORM_A PLATFORM_B <<< "$pair"

    # Check device availability
    if [ "$PLATFORM_B" = "android" ] && [ "$ANDROID_AVAILABLE" != true ]; then
        log_warn "Skipping $pair — no Android device"
        report_result "$pair" "SKIP" "0"
        continue
    fi
    if [ "$PLATFORM_B" = "ios" ] && [ "$IOS_AVAILABLE" != true ]; then
        log_warn "Skipping $pair — no iOS device"
        report_result "$pair" "SKIP" "0"
        continue
    fi

    PAIR_START=$(date +%s)
    PAIR_STATUS="FAIL"

    # Create room
    ROOM_ID=$(create_room "$SERVER_URL") || { report_result "$pair" "FAIL" "0"; continue; }
    log_info "Room created: $ROOM_ID"

    if [ "$PLATFORM_B" = "ios" ]; then
        # --- iOS-specific flow (section 5 of plan) ---
        BARRIER_DIR=$(mktemp -d)
        BARRIER_DIRS+=("$BARRIER_DIR")

        run_ios_pair() {
            local room_id="$1"
            local barrier_dir="$2"
            local holder_pid=""
            local ios_exit=0

            # Start web holder in background (runs from web/ dir for proper module resolution)
            log_info "Starting web room holder ..."
            (cd "$WEB_DIR" && \
                SMOKE_SERVER_URL="$WEB_URL" \
                SMOKE_ROOM_ID="$room_id" \
                SMOKE_BARRIER_DIR="$barrier_dir" \
                exec npx playwright test hold-room.spec.ts) &
            holder_pid=$!
            PIDS+=("$holder_pid")

            # Require holder to actually join before starting iOS.
            barrier_wait "$barrier_dir" "web.holder.joined" 45 || {
                log_error "Web holder did not join room in time"
                kill "$holder_pid" 2>/dev/null || true
                wait "$holder_pid" 2>/dev/null || true
                return 1
            }
            if ! kill -0 "$holder_pid" 2>/dev/null; then
                log_error "Web holder exited unexpectedly before iOS started"
                wait "$holder_pid" 2>/dev/null || true
                return 1
            fi

            # Run iOS test
            SMOKE_SERVER_URL="$MOBILE_URL" \
            SMOKE_ROOM_ID="$room_id" \
            SMOKE_ARTIFACTS_DIR="$SMOKE_ARTIFACTS_DIR" \
            IOS_UDID="$IOS_UDID" \
            bash "$SCRIPT_DIR/ios/smoke-ios.sh" || ios_exit=$?

            # Ensure the web leg stayed alive through the iOS run.
            if [ "$ios_exit" -eq 0 ]; then
                if ! kill -0 "$holder_pid" 2>/dev/null; then
                    log_error "Web holder exited unexpectedly during iOS run"
                    ios_exit=1
                fi
            fi

            # Kill web holder
            kill "$holder_pid" 2>/dev/null || true
            wait "$holder_pid" 2>/dev/null || true

            return "$ios_exit"
        }

        if run_ios_pair "$ROOM_ID" "$BARRIER_DIR"; then
            PAIR_STATUS="PASS"
        fi
    else
        # --- Standard barrier-synchronized flow (section 6 of plan) ---
        BARRIER_DIR=$(mktemp -d)
        BARRIER_DIRS+=("$BARRIER_DIR")

        run_standard_pair() {
            local platform_a="$1" platform_b="$2" room_id="$3"
            local pid_a="" pid_b=""

            # Kill any background processes started by this pair
            kill_pair() {
                for p in "$pid_a" "$pid_b"; do
                    if [ -n "$p" ]; then
                        kill "$p" 2>/dev/null || true
                        wait "$p" 2>/dev/null || true
                    fi
                done
            }

            # Start client A (web) in background (exec so kill reaches playwright)
            log_info "Starting $platform_a leg ..."
            (cd "$WEB_DIR" && \
                SMOKE_SERVER_URL="$WEB_URL" \
                SMOKE_ROOM_ID="$room_id" \
                SMOKE_BARRIER_DIR="$BARRIER_DIR" \
                SMOKE_ROLE="web" \
                exec npx playwright test smoke.spec.ts) &
            pid_a=$!
            PIDS+=("$pid_a")

            # Wait for A to join
            barrier_wait "$BARRIER_DIR" "web.joined" 30 || { kill_pair; return 1; }

            # Start client B (android) in background
            log_info "Starting $platform_b leg ..."
            SMOKE_SERVER_URL="$MOBILE_URL" \
            SMOKE_ROOM_ID="$room_id" \
            SMOKE_BARRIER_DIR="$BARRIER_DIR" \
            SMOKE_ARTIFACTS_DIR="$SMOKE_ARTIFACTS_DIR" \
            ANDROID_SERIAL="$ANDROID_SERIAL" \
            bash "$SCRIPT_DIR/android/smoke-android.sh" &
            pid_b=$!
            PIDS+=("$pid_b")

            # Wait for B to join
            barrier_wait "$BARRIER_DIR" "android.joined" 30 || { kill_pair; return 1; }

            # Signal both peers are ready
            barrier_write "$BARRIER_DIR" "peer.ready"

            # Wait for both in-call
            barrier_wait_all "$BARRIER_DIR" 45 "web.in-call" "android.in-call" || { kill_pair; return 1; }

            # Signal leave
            barrier_write "$BARRIER_DIR" "leave"

            # Wait for both left
            barrier_wait_all "$BARRIER_DIR" 20 "web.left" "android.left" || { kill_pair; return 1; }

            # Create new room for rejoin
            local rejoin_room_id
            rejoin_room_id=$(create_room "$SERVER_URL") || { kill_pair; return 1; }
            log_info "Rejoin room: $rejoin_room_id"

            # Signal rejoin with new room ID
            barrier_write "$BARRIER_DIR" "rejoin" "$rejoin_room_id"

            # Wait for both rejoined
            barrier_wait_all "$BARRIER_DIR" 30 "web.rejoined" "android.rejoined" || { kill_pair; return 1; }

            # Signal peers ready again
            barrier_write "$BARRIER_DIR" "peer.ready.2"

            # Wait for both in-call again
            barrier_wait_all "$BARRIER_DIR" 45 "web.rejoin-in-call" "android.rejoin-in-call" || { kill_pair; return 1; }

            # Signal end
            barrier_write "$BARRIER_DIR" "end"

            # Wait for both processes to exit
            wait "$pid_a" || { kill_pair; return 1; }
            wait "$pid_b" || { kill_pair; return 1; }

            return 0
        }

        if run_standard_pair "$PLATFORM_A" "$PLATFORM_B" "$ROOM_ID"; then
            PAIR_STATUS="PASS"
        fi
    fi

    PAIR_END=$(date +%s)
    PAIR_ELAPSED=$((PAIR_END - PAIR_START))
    report_result "$pair" "$PAIR_STATUS" "$PAIR_ELAPSED"
done

# --- Step 7: Print summary ---
print_summary
