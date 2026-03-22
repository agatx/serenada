#!/usr/bin/env bash
# Bootstrap a worktree (or any checkout) by installing all dependencies.
#
# Run this after creating a git worktree to make it build-ready.
# Also works on the main checkout or a fresh clone.
#
# Usage:
#   tools/worktree-bootstrap.sh [path]     # defaults to repo root
#   tools/worktree-bootstrap.sh ../my-wt
#
# Options (env vars):
#   SKIP_WEB=1       Skip web client (npm install)
#   SKIP_SERVER=1    Skip Go server (go mod download)
#   SKIP_ANDROID=1   Skip Android client (gradlew)
#   SKIP_IOS=1       Skip iOS client (xcodegen)
#   SKIP_ENV=1       Skip .env creation

set -euo pipefail

# --- Colors & logging (matches smoke-test/lib/common.sh) ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

die() { log_error "$@"; exit 1; }

# --- Resolve paths ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Find the main working tree (first entry from `git worktree list`)
MAIN_REPO="$(git -C "$SCRIPT_DIR" worktree list --porcelain 2>/dev/null | head -1 | sed 's/^worktree //')"
if [ -z "$MAIN_REPO" ] || [ ! -d "$MAIN_REPO" ]; then
    MAIN_REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

TARGET="${1:-$MAIN_REPO}"
ORIGINAL_TARGET="$TARGET"
if [[ "$TARGET" != /* ]]; then
    TARGET="$(cd "$TARGET" 2>/dev/null && pwd || true)"
fi

if [ -z "$TARGET" ] || [ ! -d "$TARGET" ]; then
    die "Directory not found: $ORIGINAL_TARGET"
fi

log_info "Bootstrapping: $TARGET"

# Track results for summary
RESULTS=()
record() { RESULTS+=("$1"); }

# --- .env ---
if [ "${SKIP_ENV:-}" != "1" ]; then
    if [ -f "$TARGET/.env" ]; then
        log_ok ".env already exists"
        record "env:exists"
    elif [ -f "$MAIN_REPO/.env" ] && [ "$TARGET" != "$MAIN_REPO" ]; then
        log_info "Copying .env from main repo"
        cp "$MAIN_REPO/.env" "$TARGET/.env"
        record "env:copied"
    elif [ -f "$TARGET/.env.example" ]; then
        log_info "Creating .env from .env.example"
        cp "$TARGET/.env.example" "$TARGET/.env"
        record "env:from-example"
    else
        log_warn "No .env or .env.example found"
        record "env:missing"
    fi
else
    record "env:skipped"
fi

# --- Web client ---
if [ "${SKIP_WEB:-}" != "1" ]; then
    if [ -f "$TARGET/client/package.json" ]; then
        if command -v npm >/dev/null 2>&1; then
            log_info "Installing web client dependencies..."
            if (cd "$TARGET/client" && npm ci --no-audit --no-fund 2>&1); then
                log_ok "Web client: npm ci complete"
                record "web:ok"
            else
                log_error "Web client: npm ci failed"
                record "web:failed"
            fi
        else
            log_warn "Web client: 'npm' not found in PATH"
            record "web:no-npm"
        fi
    else
        log_warn "Web client: package.json not found"
        record "web:not-found"
    fi
else
    record "web:skipped"
fi

# --- Go server ---
if [ "${SKIP_SERVER:-}" != "1" ]; then
    if [ -f "$TARGET/server/go.mod" ]; then
        if command -v go >/dev/null 2>&1; then
            log_info "Downloading Go server dependencies..."
            if (cd "$TARGET/server" && go mod download 2>&1); then
                log_ok "Go server: dependencies downloaded"
                record "server:ok"
            else
                log_error "Go server: go mod download failed"
                record "server:failed"
            fi
        else
            log_warn "Go server: 'go' not found in PATH"
            record "server:no-go"
        fi
    else
        log_warn "Go server: go.mod not found"
        record "server:not-found"
    fi
else
    record "server:skipped"
fi

# --- Android client ---
if [ "${SKIP_ANDROID:-}" != "1" ]; then
    if [ -f "$TARGET/client-android/gradlew" ]; then
        # Ensure local.properties exists (gitignored, needed for SDK path + Firebase config)
        if [ ! -f "$TARGET/client-android/local.properties" ]; then
            # Try copying from the main repo first (has SDK path + Firebase config)
            MAIN_LP="$MAIN_REPO/client-android/local.properties"
            if [ "$TARGET" != "$MAIN_REPO" ] && [ -f "$MAIN_LP" ]; then
                log_info "Copying local.properties from main repo"
                cp "$MAIN_LP" "$TARGET/client-android/local.properties"
            else
                # Generate a minimal one with the standard macOS SDK path
                SDK_PATH="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
                if [ -d "$SDK_PATH" ]; then
                    log_info "Creating local.properties (sdk.dir=$SDK_PATH)"
                    echo "sdk.dir=$SDK_PATH" > "$TARGET/client-android/local.properties"
                fi
            fi
        fi

        # Check for Android SDK
        HAS_SDK=false
        if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
            HAS_SDK=true
        elif [ -f "$TARGET/client-android/local.properties" ] && grep -q 'sdk.dir' "$TARGET/client-android/local.properties" 2>/dev/null; then
            HAS_SDK=true
        fi

        if [ "$HAS_SDK" = false ]; then
            log_warn "Android client: no SDK found (set ANDROID_HOME or create local.properties)"
            record "android:no-sdk"
        elif ! command -v java >/dev/null 2>&1; then
            log_warn "Android client: 'java' not found in PATH"
            record "android:no-java"
        else
            log_info "Syncing Android Gradle dependencies..."
            if (cd "$TARGET/client-android" && ./gradlew --no-daemon dependencies >/dev/null 2>&1); then
                log_ok "Android client: Gradle sync complete"
                record "android:ok"
            else
                log_warn "Android client: Gradle sync failed"
                record "android:failed"
            fi
        fi
    else
        log_warn "Android client: gradlew not found"
        record "android:not-found"
    fi
else
    record "android:skipped"
fi

# --- iOS client ---
if [ "${SKIP_IOS:-}" != "1" ]; then
    if [ -f "$TARGET/client-ios/project.yml" ]; then
        # Ensure GoogleService-Info.plist exists (gitignored, needed for Firebase/FCM)
        PLIST_PATH="$TARGET/client-ios/Resources/GoogleService-Info.plist"
        if [ ! -f "$PLIST_PATH" ]; then
            MAIN_PLIST="$MAIN_REPO/client-ios/Resources/GoogleService-Info.plist"
            if [ "$TARGET" != "$MAIN_REPO" ] && [ -f "$MAIN_PLIST" ]; then
                mkdir -p "$(dirname "$PLIST_PATH")"
                log_info "Copying GoogleService-Info.plist from main repo"
                cp "$MAIN_PLIST" "$PLIST_PATH"
            else
                log_warn "GoogleService-Info.plist not found (iOS push notifications won't work)"
            fi
        fi

        if command -v xcodegen >/dev/null 2>&1; then
            log_info "Generating iOS Xcode project..."
            if (cd "$TARGET/client-ios" && xcodegen generate 2>&1); then
                log_ok "iOS client: Xcode project generated"
                record "ios:ok"
            else
                log_error "iOS client: xcodegen failed"
                record "ios:failed"
            fi
        else
            log_warn "iOS client: 'xcodegen' not found (brew install xcodegen)"
            record "ios:no-xcodegen"
        fi
    else
        log_warn "iOS client: project.yml not found"
        record "ios:not-found"
    fi
else
    record "ios:skipped"
fi

# --- Summary ---
echo ""
log_info "=== Bootstrap Summary ==="

FAILURES=0
for result in "${RESULTS[@]}"; do
    component="${result%%:*}"
    status="${result#*:}"
    case "$status" in
        ok|exists|copied|from-example)
            log_ok "  $component: $status" ;;
        skipped)
            echo -e "  ${YELLOW}SKIP${NC}  $component" ;;
        no-*)
            log_warn "  $component: $status" ;;
        *)
            log_error "  $component: $status"
            FAILURES=$((FAILURES + 1)) ;;
    esac
done

echo ""
if [ "$FAILURES" -gt 0 ]; then
    log_warn "Bootstrap completed with $FAILURES issue(s). Run 'tools/worktree-validate.sh' to diagnose."
    exit 1
else
    log_ok "Bootstrap complete"
fi
