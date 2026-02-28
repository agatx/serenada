#!/bin/sh
set -eu

ROOT_DIR="${SRCROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
WEBRTC_DIR="$ROOT_DIR/Vendor/WebRTC"
WEBRTC_XCFRAMEWORK="$WEBRTC_DIR/WebRTC.xcframework"
CHECKSUM_FILE="$WEBRTC_DIR/WebRTC.xcframework.sha256"

if [ ! -d "$WEBRTC_XCFRAMEWORK" ]; then
  echo "warning: WebRTC.xcframework not found at $WEBRTC_XCFRAMEWORK. Building in stub mode."
  exit 0
fi

if [ ! -f "$CHECKSUM_FILE" ]; then
  echo "error: checksum file missing: $CHECKSUM_FILE"
  exit 1
fi

EXPECTED="$(awk 'NF {print $1; exit}' "$CHECKSUM_FILE" | tr '[:upper:]' '[:lower:]')"
if [ -z "$EXPECTED" ]; then
  echo "error: checksum file is empty: $CHECKSUM_FILE"
  exit 1
fi

ACTUAL="$(cd "$WEBRTC_DIR" && find "WebRTC.xcframework" -type f -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 | shasum -a 256 | awk '{print $1}')"

if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "error: WebRTC.xcframework checksum mismatch"
  echo "expected: $EXPECTED"
  echo "actual:   $ACTUAL"
  exit 1
fi

echo "WebRTC.xcframework checksum verified."
