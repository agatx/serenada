#!/usr/bin/env bash
set -euo pipefail

# End-to-end WebRTC iOS XCFramework build for branch-heads/7559_173 with updated
# TLS root bundle (includes ISRG roots used by Let's Encrypt).
#
# Usage:
#   bash tools/build_libwebrtc_ios_7559.sh
#
# Optional environment overrides:
#   WORKDIR=/opt/webrtc-build-ios
#   BRANCH=branch-heads/7559_173
#   FETCH_TARGET=webrtc_ios
#   BUILD_CONFIG=release
#   DEPLOYMENT_TARGET=16.0
#   IOS_ARCHS="device:arm64 simulator:arm64 simulator:x64"
#   ROOT_BUNDLE_URL=https://curl.se/ca/cacert.pem
#   OUTPUT_DIR=/opt/webrtc-build-ios/src/out_ios_libs
#   VENDOR_XCFRAMEWORK=/path/to/repo/client-ios/Vendor/WebRTC/WebRTC.xcframework
#   UPDATE_CHECKSUM=1
#   RUN_GCLIENT_HOOKS=1
#   STRIP_DSYMS=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKDIR="${WORKDIR:-/opt/webrtc-build-ios}"
BRANCH="${BRANCH:-branch-heads/7559_173}"
FETCH_TARGET="${FETCH_TARGET:-webrtc_ios}"
BUILD_CONFIG="${BUILD_CONFIG:-release}"
DEPLOYMENT_TARGET="${DEPLOYMENT_TARGET:-16.0}"
IOS_ARCHS="${IOS_ARCHS:-device:arm64 simulator:arm64 simulator:x64}"
ROOT_BUNDLE_URL="${ROOT_BUNDLE_URL:-https://curl.se/ca/cacert.pem}"
OUTPUT_DIR="${OUTPUT_DIR:-$WORKDIR/src/out_ios_libs}"
VENDOR_XCFRAMEWORK="${VENDOR_XCFRAMEWORK:-$REPO_ROOT/client-ios/Vendor/WebRTC/WebRTC.xcframework}"
UPDATE_CHECKSUM="${UPDATE_CHECKSUM:-1}"
RUN_GCLIENT_HOOKS="${RUN_GCLIENT_HOOKS:-1}"
STRIP_DSYMS="${STRIP_DSYMS:-1}"

log() {
  printf '[build-libwebrtc-ios] %s\n' "$*"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "missing required command: $1"
    exit 1
  fi
}

setup_workspace() {
  mkdir -p "$WORKDIR"
  cd "$WORKDIR"

  if [ ! -d depot_tools ]; then
    log "cloning depot_tools"
    git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
  fi

  export PATH="$WORKDIR/depot_tools:$PATH"

  if [ ! -d src ]; then
    log "fetching $FETCH_TARGET workspace"
    fetch --nohooks "$FETCH_TARGET"
  fi
}

sync_sources() {
  export PATH="$WORKDIR/depot_tools:$PATH"
  cd "$WORKDIR/src"

  log "checking out $BRANCH"
  git fetch origin
  git checkout -f "$BRANCH"

  cd "$WORKDIR"
  log "running gclient sync"
  gclient sync --with_branch_heads

  if [ "$RUN_GCLIENT_HOOKS" = "1" ]; then
    log "running gclient runhooks"
    gclient runhooks
  fi
}

patch_ssl_roots() {
  export PATH="$WORKDIR/depot_tools:$PATH"
  cd "$WORKDIR/src"

  log "generating ssl_roots.h from $ROOT_BUNDLE_URL"
  vpython3 tools_webrtc/sslroots/generate_sslroots.py "$ROOT_BUNDLE_URL"

  mv ssl_roots.h rtc_base/ssl_roots.h

  if ! grep -q "ISRG Root X1" rtc_base/ssl_roots.h; then
    log "warning: generated roots do not contain ISRG Root X1"
  fi
}

build_xcframework() {
  export PATH="$WORKDIR/depot_tools:$PATH"
  local src_dir="$WORKDIR/src"
  cd "$src_dir"

  if [[ "$OUTPUT_DIR" != "$src_dir"/* ]]; then
    log "output directory must be under $src_dir; overriding to $src_dir/out_ios_libs"
    OUTPUT_DIR="$src_dir/out_ios_libs"
  fi

  mkdir -p "$OUTPUT_DIR"
  local output_xcframework="$OUTPUT_DIR/WebRTC.xcframework"

  log "building WebRTC.xcframework (config=$BUILD_CONFIG target=$DEPLOYMENT_TARGET arches=$IOS_ARCHS)"
  # shellcheck disable=SC2206
  local arch_list=($IOS_ARCHS)

  vpython3 tools_webrtc/ios/build_ios_libs.py \
    --build_config "$BUILD_CONFIG" \
    --deployment-target "$DEPLOYMENT_TARGET" \
    --output-dir "$OUTPUT_DIR" \
    --arch "${arch_list[@]}"

  if [ ! -d "$output_xcframework" ]; then
    log "expected output missing: $output_xcframework"
    exit 1
  fi

  log "build complete"
  du -sh "$output_xcframework"
}

vendor_artifact() {
  local output_xcframework="$OUTPUT_DIR/WebRTC.xcframework"
  local info_plist="$output_xcframework/Info.plist"
  mkdir -p "$(dirname "$VENDOR_XCFRAMEWORK")"

  if [ "$STRIP_DSYMS" = "1" ]; then
    log "stripping dSYMs from XCFramework for VCS-friendly artifact size"
    while IFS= read -r dsym_dir; do
      local parent
      parent="$(basename "$(dirname "$dsym_dir")")"
      mv "$dsym_dir" "/tmp/webrtc-${parent}-dSYMs-$$"
    done < <(find "$output_xcframework" -type d -name dSYMs -prune)
    # Remove DebugSymbolsPath entries after dSYM stripping.
    /usr/libexec/PlistBuddy -c 'Delete :AvailableLibraries:0:DebugSymbolsPath' "$info_plist" >/dev/null 2>&1 || true
    /usr/libexec/PlistBuddy -c 'Delete :AvailableLibraries:1:DebugSymbolsPath' "$info_plist" >/dev/null 2>&1 || true
  fi

  log "copying artifact to $VENDOR_XCFRAMEWORK"
  rm -rf "$VENDOR_XCFRAMEWORK"
  cp -R "$output_xcframework" "$VENDOR_XCFRAMEWORK"

  if [ "$UPDATE_CHECKSUM" = "1" ]; then
    log "updating checksum file"
    (
      cd "$REPO_ROOT/client-ios"
      ./scripts/update_webrtc_checksum.sh
    )
  fi
}

main() {
  require_cmd git
  require_cmd python3
  require_cmd xcodebuild
  require_cmd shasum

  setup_workspace
  sync_sources
  patch_ssl_roots
  build_xcframework
  vendor_artifact

  log "done"
}

main "$@"
