#!/usr/bin/env bash
set -euo pipefail

# End-to-end WebRTC Android AAR build for branch-heads/7559_173 with updated
# TLS root bundle (includes ISRG roots used by Let's Encrypt).
#
# Usage:
#   bash tools/build_libwebrtc_android_7559.sh
#
# Optional environment overrides:
#   WORKDIR=/opt/webrtc-build
#   BRANCH=branch-heads/7559_173
#   ARCHS="armeabi-v7a arm64-v8a x86 x86_64"
#   FETCH_ARGS="--nohooks --no-history"
#   ROOT_BUNDLE_URL=https://curl.se/ca/cacert.pem
#   OUTPUT_AAR=/opt/webrtc-build/artifacts/libwebrtc-7559_173-universal-curlroots.aar

WORKDIR="${WORKDIR:-/opt/webrtc-build}"
BRANCH="${BRANCH:-branch-heads/7559_173}"
ARCHS="${ARCHS:-armeabi-v7a arm64-v8a x86 x86_64}"
FETCH_ARGS="${FETCH_ARGS:---nohooks --no-history}"
ROOT_BUNDLE_URL="${ROOT_BUNDLE_URL:-https://curl.se/ca/cacert.pem}"
OUTPUT_AAR="${OUTPUT_AAR:-$WORKDIR/artifacts/libwebrtc-7559_173-universal-curlroots.aar}"

log() {
  printf '[build-libwebrtc] %s\n' "$*"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "missing required command: $1"
    exit 1
  fi
}

install_deps() {
  if command -v apt-get >/dev/null 2>&1; then
    log "installing apt dependencies"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
      git python3 curl unzip xz-utils ca-certificates \
      build-essential file pkg-config jq
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
    log "fetching webrtc_android workspace"
    # Avoid pulling full git history; this cuts disk usage materially on smaller
    # build hosts while still allowing the branch checkout below.
    # shellcheck disable=SC2086
    fetch $FETCH_ARGS webrtc_android
  fi
}

sync_sources() {
  export PATH="$WORKDIR/depot_tools:$PATH"
  cd "$WORKDIR/src"

  local remote_ref="$BRANCH"
  if [[ "$BRANCH" == branch-heads/* ]]; then
    remote_ref="refs/$BRANCH"
  fi

  log "checking out $BRANCH"
  git fetch origin "$remote_ref:refs/remotes/origin/$BRANCH"
  git checkout -f "$BRANCH"

  cd "$WORKDIR"
  log "running gclient sync"
  gclient sync --with_branch_heads --no-history
  log "running gclient runhooks"
  gclient runhooks
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

build_aar() {
  export PATH="$WORKDIR/depot_tools:$PATH"
  cd "$WORKDIR/src"

  mkdir -p "$(dirname "$OUTPUT_AAR")"

  log "building AAR (archs=$ARCHS) -> $OUTPUT_AAR"
  # build_aar.py expects a single --arch followed by all requested ABIs.
  # Repeating --arch causes argparse to keep only the last occurrence.
  # shellcheck disable=SC2086
  vpython3 tools_webrtc/android/build_aar.py \
    --arch $ARCHS \
    --output "$OUTPUT_AAR"

  log "build complete"
  ls -lh "$OUTPUT_AAR"
  sha256sum "$OUTPUT_AAR"
}

main() {
  require_cmd git
  require_cmd python3
  require_cmd curl

  install_deps
  setup_workspace
  sync_sources
  patch_ssl_roots
  build_aar
}

main "$@"
