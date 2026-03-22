#!/usr/bin/env bash
# Validate that a worktree (or the main repo) has all components functional.
#
# Usage:
#   tools/worktree-validate.sh [path]     # defaults to repo root
#   tools/worktree-validate.sh ../my-wt
#
# Options (env vars):
#   SKIP_WEB=1       Skip web client checks
#   SKIP_SERVER=1    Skip Go server checks
#   SKIP_ANDROID=1   Skip Android client checks
#   SKIP_IOS=1       Skip iOS client checks
#   SKIP_BUILD=1     Skip compilation checks (only verify deps/structure)
#   SKIP_TEST=1      Skip test execution
#   VERBOSE=1        Show command output

set -euo pipefail

# --- Colors & logging ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[PASS]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }
log_skip()  { echo -e "${YELLOW}[SKIP]${NC}  $*"; }

VERBOSE="${VERBOSE:-0}"

# Redirect output based on verbosity
run_quiet() {
    if [ "$VERBOSE" = "1" ]; then
        "$@"
    else
        "$@" >/dev/null 2>&1
    fi
}

# --- Resolve target directory ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET="${1:-$DEFAULT_ROOT}"
ORIGINAL_TARGET="$TARGET"
if [[ "$TARGET" != /* ]]; then
    TARGET="$(cd "$TARGET" 2>/dev/null && pwd || true)"
fi

if [ -z "$TARGET" ] || [ ! -d "$TARGET" ]; then
    echo "Directory not found: $ORIGINAL_TARGET"
    exit 1
fi

echo -e "${BOLD}Validating: $TARGET${NC}"
echo ""

# --- Counters ---
PASS=0
FAIL=0
WARN=0
SKIP=0

check_pass() { log_ok "$1"; PASS=$((PASS + 1)); }
check_fail() { log_fail "$1"; FAIL=$((FAIL + 1)); }
check_warn() { log_warn "$1"; WARN=$((WARN + 1)); }
check_skip() { log_skip "$1"; SKIP=$((SKIP + 1)); }

# ============================================================
# Section: Repository structure
# ============================================================
echo -e "${BOLD}--- Repository Structure ---${NC}"

# Git
if git -C "$TARGET" rev-parse --git-dir >/dev/null 2>&1; then
    BRANCH=$(git -C "$TARGET" branch --show-current 2>/dev/null || echo "(detached)")
    check_pass "Git repository (branch: $BRANCH)"
else
    check_fail "Not a git repository"
fi

# .env
if [ -f "$TARGET/.env" ]; then
    # Check for placeholder secrets
    if grep -qE 'dev-secret|dev-room-id-secret|change-me' "$TARGET/.env" 2>/dev/null; then
        check_warn ".env exists but contains placeholder secrets"
    else
        check_pass ".env configured"
    fi
elif [ -f "$TARGET/.env.example" ]; then
    check_warn ".env missing (run: cp .env.example .env)"
else
    check_fail ".env and .env.example both missing"
fi

# Key directories
for dir in client server client-android client-ios; do
    if [ -d "$TARGET/$dir" ]; then
        check_pass "Directory: $dir/"
    else
        check_fail "Directory missing: $dir/"
    fi
done

# Cross-platform resilience constants
if [ -f "$TARGET/scripts/check-resilience-constants.mjs" ]; then
    if command -v node >/dev/null 2>&1; then
        if run_quiet node "$TARGET/scripts/check-resilience-constants.mjs"; then
            check_pass "Resilience constants parity"
        else
            check_fail "Resilience constants out of sync"
        fi
    else
        check_warn "Resilience constants: 'node' not found, cannot verify"
    fi
fi

# ============================================================
# Section: Web client
# ============================================================
echo ""
echo -e "${BOLD}--- Web Client ---${NC}"

if [ "${SKIP_WEB:-}" = "1" ]; then
    check_skip "Web client (SKIP_WEB=1)"
else
    CLIENT="$TARGET/client"

    # node_modules
    if [ -d "$CLIENT/node_modules" ]; then
        check_pass "node_modules installed"
    else
        check_fail "node_modules missing (run: cd client && npm install)"
    fi

    # Workspace packages
    for pkg in core react-ui; do
        if [ -d "$CLIENT/packages/$pkg" ]; then
            check_pass "Package: @serenada/$pkg"
        else
            check_fail "Package missing: packages/$pkg"
        fi
    done

    # Check npm is available for build/lint/test steps
    HAS_NPM=false
    if command -v npm >/dev/null 2>&1; then
        HAS_NPM=true
    fi

    # TypeScript build
    if [ "${SKIP_BUILD:-}" != "1" ]; then
        if [ "$HAS_NPM" != true ]; then
            check_skip "Web build ('npm' not found)"
        elif [ -d "$CLIENT/node_modules" ]; then
            log_info "Building web client..."
            if (cd "$CLIENT" && run_quiet npm run build); then
                check_pass "TypeScript + Vite build"
            else
                check_fail "Build failed (npm run build)"
            fi
        else
            check_skip "Build: node_modules missing"
        fi
    else
        check_skip "Web build (SKIP_BUILD=1)"
    fi

    # Lint
    if [ "${SKIP_BUILD:-}" != "1" ]; then
        if [ "$HAS_NPM" != true ]; then
            check_skip "Web lint ('npm' not found)"
        elif [ -d "$CLIENT/node_modules" ]; then
            if (cd "$CLIENT" && run_quiet npm run lint); then
                check_pass "ESLint"
            else
                check_warn "ESLint errors found"
            fi
        else
            check_skip "Lint: node_modules missing"
        fi
    else
        check_skip "Web lint (SKIP_BUILD=1)"
    fi

    # Tests
    if [ "${SKIP_TEST:-}" != "1" ]; then
        if [ "$HAS_NPM" != true ]; then
            check_skip "Web tests ('npm' not found)"
        elif [ -d "$CLIENT/node_modules" ]; then
            log_info "Running web tests..."
            if (cd "$CLIENT" && run_quiet npm test -- --run); then
                check_pass "Vitest"
            else
                check_fail "Tests failed (npm test)"
            fi
        else
            check_skip "Tests: node_modules missing"
        fi
    else
        check_skip "Web tests (SKIP_TEST=1)"
    fi
fi

# ============================================================
# Section: Go server
# ============================================================
echo ""
echo -e "${BOLD}--- Go Server ---${NC}"

if [ "${SKIP_SERVER:-}" = "1" ]; then
    check_skip "Go server (SKIP_SERVER=1)"
else
    SERVER="$TARGET/server"

    # Go toolchain
    if command -v go >/dev/null 2>&1; then
        GO_VERSION=$(go version | grep -oE 'go[0-9]+\.[0-9]+' | head -1)
        check_pass "Go installed ($GO_VERSION)"

        # Check minimum version (1.24)
        GO_MINOR=$(echo "$GO_VERSION" | grep -oE '[0-9]+\.[0-9]+' | cut -d. -f2)
        if [ "${GO_MINOR:-0}" -lt 24 ]; then
            check_warn "Go 1.24+ required (found $GO_VERSION)"
        fi
    else
        check_fail "Go not installed"
    fi

    # go.mod/go.sum
    if [ -f "$SERVER/go.mod" ] && [ -f "$SERVER/go.sum" ]; then
        check_pass "go.mod + go.sum present"
    else
        check_fail "go.mod or go.sum missing"
    fi

    # Build
    if [ "${SKIP_BUILD:-}" != "1" ] && command -v go >/dev/null 2>&1; then
        log_info "Building Go server..."
        GO_BUILD_OUT=$(mktemp "${TMPDIR:-/tmp}/serenada-server.XXXXXX")
        if (cd "$SERVER" && run_quiet go build -o "$GO_BUILD_OUT" .); then
            check_pass "Go build"
        else
            check_fail "Go build failed"
        fi
        rm -f "$GO_BUILD_OUT"
    elif [ "${SKIP_BUILD:-}" = "1" ]; then
        check_skip "Go build (SKIP_BUILD=1)"
    fi

    # Tests
    if [ "${SKIP_TEST:-}" != "1" ] && command -v go >/dev/null 2>&1; then
        log_info "Running Go tests..."
        if (cd "$SERVER" && run_quiet go test ./...); then
            check_pass "Go tests"
        else
            check_fail "Go tests failed"
        fi
    elif [ "${SKIP_TEST:-}" = "1" ]; then
        check_skip "Go tests (SKIP_TEST=1)"
    fi
fi

# ============================================================
# Section: Android client
# ============================================================
echo ""
echo -e "${BOLD}--- Android Client ---${NC}"

if [ "${SKIP_ANDROID:-}" = "1" ]; then
    check_skip "Android client (SKIP_ANDROID=1)"
else
    ANDROID="$TARGET/client-android"

    # Gradle wrapper
    if [ -x "$ANDROID/gradlew" ]; then
        check_pass "Gradle wrapper (executable)"
    elif [ -f "$ANDROID/gradlew" ]; then
        check_warn "Gradle wrapper exists but not executable (run: chmod +x gradlew)"
    else
        check_fail "Gradle wrapper missing"
    fi

    # WebRTC AAR
    AAR=$(find "$ANDROID" -name "*.aar" -path "*/libs/*" 2>/dev/null | head -1)
    if [ -n "$AAR" ]; then
        CHECKSUM_FILE="${AAR}.sha256"
        if [ -f "$CHECKSUM_FILE" ]; then
            check_pass "WebRTC AAR + checksum file"
        else
            check_warn "WebRTC AAR found but no .sha256 checksum file"
        fi
    else
        check_warn "WebRTC AAR not found in libs/"
    fi

    # Java/JDK
    if command -v java >/dev/null 2>&1; then
        JAVA_VERSION=$(java -version 2>&1 | head -1)
        check_pass "Java installed ($JAVA_VERSION)"
    else
        check_warn "Java not installed (needed for Gradle)"
    fi

    # Android SDK
    HAS_ANDROID_SDK=false
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        check_pass "Android SDK (\$ANDROID_HOME: $ANDROID_HOME)"
        HAS_ANDROID_SDK=true
    elif [ -f "$ANDROID/local.properties" ] && grep -q 'sdk.dir' "$ANDROID/local.properties" 2>/dev/null; then
        check_pass "Android SDK (via local.properties)"
        HAS_ANDROID_SDK=true
    else
        check_warn "Android SDK not found (set ANDROID_HOME or create local.properties)"
    fi

    # Build
    if [ "${SKIP_BUILD:-}" != "1" ] && [ -x "$ANDROID/gradlew" ] && command -v java >/dev/null 2>&1; then
        if [ "$HAS_ANDROID_SDK" = true ]; then
            log_info "Checking Android build (assembleDebug)..."
            if (cd "$ANDROID" && run_quiet ./gradlew --no-daemon assembleDebug); then
                check_pass "Android assembleDebug"
            else
                check_fail "Android assembleDebug failed"
            fi
        else
            check_skip "Android build (no SDK)"
        fi
    elif [ "${SKIP_BUILD:-}" = "1" ]; then
        check_skip "Android build (SKIP_BUILD=1)"
    fi

    # Tests
    if [ "${SKIP_TEST:-}" != "1" ] && [ -x "$ANDROID/gradlew" ] && command -v java >/dev/null 2>&1; then
        if [ "$HAS_ANDROID_SDK" = true ]; then
            log_info "Running Android unit tests..."
            if (cd "$ANDROID" && run_quiet ./gradlew --no-daemon :app:testDebugUnitTest); then
                check_pass "Android unit tests"
            else
                check_fail "Android unit tests failed"
            fi
        else
            check_skip "Android tests (no SDK)"
        fi
    elif [ "${SKIP_TEST:-}" = "1" ]; then
        check_skip "Android tests (SKIP_TEST=1)"
    fi
fi

# ============================================================
# Section: iOS client
# ============================================================
echo ""
echo -e "${BOLD}--- iOS Client ---${NC}"

if [ "${SKIP_IOS:-}" = "1" ]; then
    check_skip "iOS client (SKIP_IOS=1)"
else
    IOS="$TARGET/client-ios"

    # XcodeGen
    if command -v xcodegen >/dev/null 2>&1; then
        check_pass "xcodegen installed"
    else
        check_warn "xcodegen not installed (brew install xcodegen)"
    fi

    # project.yml
    if [ -f "$IOS/project.yml" ]; then
        check_pass "project.yml present"
    else
        check_fail "project.yml missing"
    fi

    # Xcode project (generated)
    if [ -d "$IOS/SerenadaiOS.xcodeproj" ]; then
        check_pass "Xcode project generated"
    else
        check_warn "Xcode project not generated (run: cd client-ios && xcodegen generate)"
    fi

    # WebRTC XCFramework
    if [ -d "$IOS/Vendor/WebRTC/WebRTC.xcframework" ]; then
        # Checksum verification
        if [ -f "$IOS/scripts/verify_webrtc_checksum.sh" ]; then
            if run_quiet sh "$IOS/scripts/verify_webrtc_checksum.sh"; then
                check_pass "WebRTC.xcframework checksum verified"
            else
                check_fail "WebRTC.xcframework checksum mismatch"
            fi
        else
            check_pass "WebRTC.xcframework present (no checksum script)"
        fi
    else
        check_warn "WebRTC.xcframework not found"
    fi

    # xcodebuild
    if command -v xcodebuild >/dev/null 2>&1; then
        check_pass "Xcode command-line tools installed"
    else
        check_warn "xcodebuild not found"
    fi

    # Resolve a simulator destination (pick first available iPhone 16 by device ID)
    IOS_SIM_ID=""
    if command -v xcrun >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
        IOS_SIM_ID=$(xcrun simctl list devices available -j 2>/dev/null \
            | python3 -c "
import sys, json
data = json.load(sys.stdin)
for runtime, devices in data.get('devices', {}).items():
    if 'iOS' not in runtime:
        continue
    for d in devices:
        if d.get('name') == 'iPhone 16' and d.get('isAvailable'):
            print(d['udid'])
            sys.exit(0)
" 2>/dev/null || true)
    fi

    if [ -n "$IOS_SIM_ID" ]; then
        IOS_DEST="platform=iOS Simulator,id=$IOS_SIM_ID"
    else
        IOS_DEST="platform=iOS Simulator,name=iPhone 16"
    fi

    # Build (only if project exists)
    if [ "${SKIP_BUILD:-}" != "1" ] && [ -d "$IOS/SerenadaiOS.xcodeproj" ] && command -v xcodebuild >/dev/null 2>&1; then
        log_info "Building iOS (simulator)..."
        if (cd "$IOS" && run_quiet xcodebuild build \
            -project SerenadaiOS.xcodeproj \
            -scheme SerenadaiOS \
            -destination "$IOS_DEST" \
            -quiet \
            CODE_SIGNING_ALLOWED=NO); then
            check_pass "iOS simulator build"
        else
            check_fail "iOS simulator build failed"
        fi
    elif [ "${SKIP_BUILD:-}" = "1" ]; then
        check_skip "iOS build (SKIP_BUILD=1)"
    fi

    # Tests (unit tests only — UI tests require a live server and have known flaky failures)
    if [ "${SKIP_TEST:-}" != "1" ] && [ -d "$IOS/SerenadaiOS.xcodeproj" ] && command -v xcodebuild >/dev/null 2>&1; then
        log_info "Running iOS unit tests..."
        if (cd "$IOS" && run_quiet xcodebuild test \
            -project SerenadaiOS.xcodeproj \
            -scheme SerenadaiOS \
            -destination "$IOS_DEST" \
            -only-testing:SerenadaiOSTests \
            -quiet); then
            check_pass "iOS unit tests"
        else
            check_fail "iOS unit tests failed"
        fi
    elif [ "${SKIP_TEST:-}" = "1" ]; then
        check_skip "iOS tests (SKIP_TEST=1)"
    fi
fi

# ============================================================
# Section: Docker
# ============================================================
echo ""
echo -e "${BOLD}--- Docker ---${NC}"

if [ -f "$TARGET/docker-compose.yml" ]; then
    check_pass "docker-compose.yml present"
else
    check_warn "docker-compose.yml missing"
fi

if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
        check_pass "Docker daemon running"
    else
        check_warn "Docker installed but daemon not running"
    fi
else
    check_warn "Docker not installed"
fi

# ============================================================
# Summary
# ============================================================
echo ""
TOTAL=$((PASS + FAIL + WARN + SKIP))
echo -e "${BOLD}=== Validation Summary ===${NC}"
echo -e "  ${GREEN}PASS${NC}: $PASS  ${RED}FAIL${NC}: $FAIL  ${YELLOW}WARN${NC}: $WARN  SKIP: $SKIP  (total: $TOTAL)"
echo ""

if [ "$FAIL" -gt 0 ]; then
    log_fail "Validation completed with $FAIL failure(s)"
    exit 1
elif [ "$WARN" -gt 0 ]; then
    log_warn "Validation passed with $WARN warning(s)"
    exit 0
else
    log_ok "All checks passed"
    exit 0
fi
