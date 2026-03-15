#!/usr/bin/env bash
# iOS smoke test leg — xcodebuild wrapper for DeepLinkRejoinFlowUITests
#
# Required env vars:
#   SMOKE_SERVER_URL    — Server URL (e.g. http://192.168.1.5)
#   SMOKE_ROOM_ID       — Room ID for the test
#   SMOKE_ARTIFACTS_DIR — Directory for xcresult bundle
#
# Optional:
#   IOS_UDID            — Device UDID (auto-detected if not set)
#   SMOKE_IOS_TEST_CLASS — UI test class to run (default: DeepLinkRejoinFlowUITests)
#   SMOKE_EXPECTED_PARTICIPANTS — forwarded to participant-count UI test

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"
source "$SCRIPT_DIR/../lib/device-detect.sh"

SERVER_URL="${SMOKE_SERVER_URL:?}"
ROOM_ID="${SMOKE_ROOM_ID:?}"
ARTIFACTS_DIR="${SMOKE_ARTIFACTS_DIR:-$SCRIPT_DIR/../artifacts}"
REPO_ROOT="$(repo_root)"

# Resolve signing configuration (mirrors deploy_to_device.sh)
XCCONFIG_PATH="${XCODE_XCCONFIG:-}"
DEFAULT_LOCAL_XCCONFIG="$REPO_ROOT/client-ios/LocalSigning.xcconfig"
if [ -z "$XCCONFIG_PATH" ] && [ -f "$DEFAULT_LOCAL_XCCONFIG" ]; then
    XCCONFIG_PATH="$DEFAULT_LOCAL_XCCONFIG"
fi
TEAM_ID="${DEVELOPMENT_TEAM:-}"
DERIVED_DATA_PATH="${DERIVED_DATA_PATH:-/tmp/connected-ios-smoke-build}"
SMOKE_IOS_TEST_CLASS="${SMOKE_IOS_TEST_CLASS:-DeepLinkRejoinFlowUITests}"

log_info "=== iOS Smoke Test ==="

# Detect device UDID
UDID="${IOS_UDID:-}"
if [ -z "$UDID" ]; then
    UDID=$(detect_ios) || {
        log_error "No iOS device available"
        exit 1
    }
fi
log_info "Using iOS device: $UDID"

# Generate Xcode project
log_info "Generating Xcode project ..."
(cd "$REPO_ROOT/client-ios" && xcodegen generate) || {
    log_error "xcodegen generate failed"
    exit 1
}

# Build deep link URL
DEEP_LINK="${SERVER_URL}/call/${ROOM_ID}"
log_info "Test deep link: $DEEP_LINK"

# Create artifacts dir and clean previous xcresult
mkdir -p "$ARTIFACTS_DIR"
rm -rf "$ARTIFACTS_DIR/ios-smoke.xcresult"

# Build xcodebuild arguments (signing mirrors deploy_to_device.sh)
XCODEBUILD_ARGS=(
    -project "$REPO_ROOT/client-ios/SerenadaiOS.xcodeproj"
    -scheme SerenadaiOS
    -destination "id=$UDID"
    "-only-testing:SerenadaiOSUITests/${SMOKE_IOS_TEST_CLASS}"
    -resultBundlePath "$ARTIFACTS_DIR/ios-smoke.xcresult"
    -derivedDataPath "$DERIVED_DATA_PATH"
    -allowProvisioningUpdates
)

if [ -n "$XCCONFIG_PATH" ]; then
    log_info "Using xcconfig: $XCCONFIG_PATH"
    XCODEBUILD_ARGS+=(-xcconfig "$XCCONFIG_PATH")
fi

XCODEBUILD_ARGS+=(test CODE_SIGN_STYLE=Automatic)

if [ -n "$TEAM_ID" ]; then
    log_info "Using development team: $TEAM_ID"
    XCODEBUILD_ARGS+=(DEVELOPMENT_TEAM="$TEAM_ID")
fi

# Run XCUITest on the physical device
# Disable set -e around the pipeline so we can capture PIPESTATUS
log_info "Running ${SMOKE_IOS_TEST_CLASS} on device $UDID ..."
set +e
SERENADA_UI_TEST_REJOIN_DEEPLINK="$DEEP_LINK" \
TEST_RUNNER_SERENADA_UI_TEST_REJOIN_DEEPLINK="$DEEP_LINK" \
SERENADA_UI_TEST_PARTICIPANT_COUNT_DEEPLINK="$DEEP_LINK" \
TEST_RUNNER_SERENADA_UI_TEST_PARTICIPANT_COUNT_DEEPLINK="$DEEP_LINK" \
SERENADA_UI_TEST_EXPECTED_PARTICIPANTS="${SMOKE_EXPECTED_PARTICIPANTS:-3}" \
xcodebuild "${XCODEBUILD_ARGS[@]}" 2>&1 | tail -20
EXIT_CODE=${PIPESTATUS[0]}
set -e

if [ "$EXIT_CODE" -eq 0 ]; then
    log_ok "=== iOS Smoke Test PASSED ==="
else
    log_error "=== iOS Smoke Test FAILED (exit code: $EXIT_CODE) ==="
    exit "$EXIT_CODE"
fi
