#!/bin/sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${PROJECT:-SerenadaiOS.xcodeproj}"
SCHEME="${SCHEME:-SerenadaiOS}"
CONFIGURATION="${CONFIGURATION:-Debug}"
DERIVED_DATA_PATH="${DERIVED_DATA_PATH:-/tmp/connected-ios-device-build}"
TEAM_ID="${DEVELOPMENT_TEAM:-U5TBRZ56DZ}"
UDID="${IOS_DEVICE_UDID:-}"
SHOULD_LAUNCH=1

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Build, install, and optionally launch the iOS app on a connected real device.

Options:
  --udid <device-udid>       Target device UDID (auto-detects first paired device by default)
  --team <development-team>  Development team for code signing (default: $TEAM_ID)
  --configuration <name>     Xcode build configuration (default: $CONFIGURATION)
  --derived-data <path>      Derived data output path (default: $DERIVED_DATA_PATH)
  --no-launch                Install only; do not launch app after install
  -h, --help                 Show this help
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --udid)
      UDID="$2"
      shift 2
      ;;
    --team)
      TEAM_ID="$2"
      shift 2
      ;;
    --configuration)
      CONFIGURATION="$2"
      shift 2
      ;;
    --derived-data)
      DERIVED_DATA_PATH="$2"
      shift 2
      ;;
    --no-launch)
      SHOULD_LAUNCH=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "error: xcodebuild not found" >&2
  exit 1
fi

if ! command -v xcrun >/dev/null 2>&1; then
  echo "error: xcrun not found" >&2
  exit 1
fi

if [ -z "$UDID" ]; then
  DEVICE_JSON="$(xcrun devicectl list devices --json-output - 2>/dev/null | sed -n '/^{/,$p')"
  if [ -n "$DEVICE_JSON" ]; then
    UDID="$(printf '%s' "$DEVICE_JSON" | plutil -extract result.devices.0.hardwareProperties.udid raw - 2>/dev/null || true)"
  fi
fi

if [ -z "$UDID" ]; then
  echo "error: no connected paired iOS device detected. Pass --udid <device-udid>." >&2
  exit 1
fi

cd "$ROOT_DIR"

echo "Using device UDID: $UDID"
echo "Building $SCHEME ($CONFIGURATION)..."

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -destination "id=$UDID" \
  -configuration "$CONFIGURATION" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  CODE_SIGN_STYLE=Automatic \
  build

APP_PATH="$DERIVED_DATA_PATH/Build/Products/$CONFIGURATION-iphoneos/$SCHEME.app"
if [ ! -d "$APP_PATH" ]; then
  echo "error: built app not found at $APP_PATH" >&2
  exit 1
fi

echo "Installing $APP_PATH..."
xcrun devicectl device install app --device "$UDID" "$APP_PATH"

if [ "$SHOULD_LAUNCH" -eq 1 ]; then
  BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP_PATH/Info.plist" 2>/dev/null || true)"
  if [ -z "$BUNDLE_ID" ]; then
    echo "warning: could not resolve bundle identifier; skipping launch" >&2
    exit 0
  fi

  echo "Launching $BUNDLE_ID..."
  xcrun devicectl device process launch --device "$UDID" "$BUNDLE_ID"
fi

echo "Done."
