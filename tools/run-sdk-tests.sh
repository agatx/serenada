#!/usr/bin/env bash
# Run all SDK tests across Web, Server, Android, and iOS.
# Usage:
#   tools/run-sdk-tests.sh              # run all
#   SKIP_WEB=1 tools/run-sdk-tests.sh   # skip web tests
#   SKIP_SERVER=1 SKIP_ANDROID=1 SKIP_IOS=1 tools/run-sdk-tests.sh  # web only
#
# Environment variables:
#   SKIP_WEB=1       Skip web SDK tests (core + react-ui)
#   SKIP_SERVER=1    Skip Go server tests
#   SKIP_ANDROID=1   Skip Android SDK tests
#   SKIP_IOS=1       Skip iOS SDK tests

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

passed=()
failed=()
skipped=()

run_suite() {
  local name="$1"
  shift
  echo ""
  echo "========================================"
  echo "  $name"
  echo "========================================"
  if "$@"; then
    passed+=("$name")
  else
    failed+=("$name")
  fi
}

# ── Web SDK ──────────────────────────────────────────────────────────
if [[ "${SKIP_WEB:-}" != "1" ]]; then
  run_suite "Web SDK (core)" bash -c "cd '$REPO_ROOT/client/packages/core' && npm test"
  run_suite "Web SDK (react-ui)" bash -c "cd '$REPO_ROOT/client/packages/react-ui' && npm test"
else
  skipped+=("Web SDK")
fi

# ── Go Server ────────────────────────────────────────────────────────
if [[ "${SKIP_SERVER:-}" != "1" ]]; then
  run_suite "Go Server" bash -c "cd '$REPO_ROOT/server' && go test ./..."
else
  skipped+=("Go Server")
fi

# ── Android SDK ──────────────────────────────────────────────────────
if [[ "${SKIP_ANDROID:-}" != "1" ]]; then
  run_suite "Android SDK" bash -c "cd '$REPO_ROOT/client-android' && ./gradlew test"
else
  skipped+=("Android SDK")
fi

# ── iOS SDK ──────────────────────────────────────────────────────────
if [[ "${SKIP_IOS:-}" != "1" ]]; then
  IOS_SIM_LINE=$("$REPO_ROOT/tools/resolve-ios-simulator.sh" || true)
  if [[ -z "$IOS_SIM_LINE" ]]; then
    echo "  ⚠ No available iPhone simulator found — skipping iOS tests"
    skipped+=("iOS SDK (no simulator)")
  else
    IOS_SIM_UDID=$(echo "$IOS_SIM_LINE" | cut -f1)
    IOS_SIM_NAME=$(echo "$IOS_SIM_LINE" | cut -f2)
    IOS_SIM_OS=$(echo "$IOS_SIM_LINE" | cut -f3)
    echo "  Using simulator: $IOS_SIM_NAME (iOS $IOS_SIM_OS)"
    if [[ -n "$IOS_SIM_UDID" ]]; then
      IOS_XCODE_DEST="platform=iOS Simulator,id=$IOS_SIM_UDID"
    else
      IOS_XCODE_DEST="platform=iOS Simulator,name=$IOS_SIM_NAME,OS=$IOS_SIM_OS"
    fi
    run_suite "iOS SDK" bash -c "
      cd '$REPO_ROOT/client-ios' &&
      xcodegen generate -q 2>/dev/null &&
      xcodebuild test \
        -project SerenadaiOS.xcodeproj \
        -scheme SerenadaiOS \
        -destination '$IOS_XCODE_DEST' \
        -only-testing:SerenadaiOSTests \
        -quiet
    "
  fi
else
  skipped+=("iOS SDK")
fi

# ── Summary ──────────────────────────────────────────────────────────
echo ""
echo "========================================"
echo "  Summary"
echo "========================================"
for s in "${passed[@]+"${passed[@]}"}"; do echo "  PASS  $s"; done
for s in "${failed[@]+"${failed[@]}"}"; do echo "  FAIL  $s"; done
for s in "${skipped[@]+"${skipped[@]}"}"; do echo "  SKIP  $s"; done
echo "========================================"
echo "  ${#passed[@]} passed, ${#failed[@]} failed, ${#skipped[@]} skipped"
echo "========================================"

[[ ${#failed[@]} -eq 0 ]]
