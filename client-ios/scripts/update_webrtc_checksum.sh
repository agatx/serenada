#!/bin/sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WEBRTC_DIR="$ROOT_DIR/Vendor/WebRTC"
WEBRTC_XCFRAMEWORK="$WEBRTC_DIR/WebRTC.xcframework"
CHECKSUM_FILE="$WEBRTC_DIR/WebRTC.xcframework.sha256"

if [ ! -d "$WEBRTC_XCFRAMEWORK" ]; then
  echo "WebRTC.xcframework not found at $WEBRTC_XCFRAMEWORK"
  exit 1
fi

CHECKSUM="$(cd "$WEBRTC_DIR" && find "WebRTC.xcframework" -type f -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 | shasum -a 256 | awk '{print $1}')"
echo "$CHECKSUM" > "$CHECKSUM_FILE"
echo "Updated $CHECKSUM_FILE"
