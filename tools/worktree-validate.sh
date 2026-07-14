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
#   SKIP_ENV=1       Skip .env checks
#   SKIP_DOCKER=1    Skip Docker checks
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
if [ "${SKIP_ENV:-}" = "1" ]; then
    check_skip ".env (SKIP_ENV=1)"
elif [ -f "$TARGET/.env" ]; then
    # Check for placeholder secrets (ignore commented lines)
    if grep -v '^\s*#' "$TARGET/.env" 2>/dev/null | grep -qE 'dev-secret|dev-room-id-secret|change-me'; then
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

    # local.properties (gitignored — bootstrap copies from main repo)
    if [ -f "$ANDROID/local.properties" ]; then
        check_pass "local.properties present"
    else
        check_warn "local.properties missing (run: tools/worktree-bootstrap.sh)"
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

    # WebRTC SPM dependency (zello-ios-web-rtc): manifest declares it and the
    # committed lockfile pins the same version the manifest asks for.
    MANIFEST="$IOS/SerenadaCore/Package.swift"
    RESOLVED="$IOS/SerenadaCore/Package.resolved"
    if grep -q "zello-ios-web-rtc" "$MANIFEST" 2>/dev/null; then
        check_pass "WebRTC SPM dependency declared (zello-ios-web-rtc)"
        MANIFEST_VERSION=$(sed -n 's/.*zello-ios-web-rtc[^)]*exact: *"\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
        # grep exits non-zero on no match; guard it or set -e kills the script
        # before the missing-lockfile branches below can report FAIL.
        RESOLVED_VERSION=$(grep -A6 '"identity" : "zello-ios-web-rtc"' "$RESOLVED" 2>/dev/null | sed -n 's/.*"version" : "\([^"]*\)".*/\1/p' | head -1 || true)
        if [ -z "$MANIFEST_VERSION" ]; then
            check_warn "WebRTC dependency is not pinned with exact: in SerenadaCore/Package.swift"
        elif [ ! -f "$RESOLVED" ]; then
            check_fail "SerenadaCore/Package.resolved missing (re-resolve and commit the lockfile)"
        elif [ -z "$RESOLVED_VERSION" ]; then
            check_fail "SerenadaCore/Package.resolved has no zello-ios-web-rtc pin (re-resolve and commit)"
        elif [ "$MANIFEST_VERSION" = "$RESOLVED_VERSION" ]; then
            check_pass "WebRTC pin consistent (exact: $MANIFEST_VERSION == resolved $RESOLVED_VERSION)"
        else
            check_fail "WebRTC pin drift: manifest exact: $MANIFEST_VERSION vs resolved $RESOLVED_VERSION"
        fi
    else
        check_fail "WebRTC SPM dependency missing from SerenadaCore/Package.swift"
    fi

    # GoogleService-Info.plist (gitignored — bootstrap copies from main repo)
    PLIST_PATH="$IOS/Resources/GoogleService-Info.plist"
    if [ -f "$PLIST_PATH" ]; then
        check_pass "GoogleService-Info.plist present"
    else
        check_warn "GoogleService-Info.plist missing (push notifications won't work)"
    fi

    # xcodebuild
    if command -v xcodebuild >/dev/null 2>&1; then
        check_pass "Xcode command-line tools installed"
    else
        check_warn "xcodebuild not found"
    fi

    # Resolve a simulator destination using the shared helper
    IOS_SIM_ID=""
    IOS_SIM_NAME=""
    IOS_SIM_OS=""
    RESOLVE_SCRIPT="$SCRIPT_DIR/resolve-ios-simulator.sh"
    if [ -x "$RESOLVE_SCRIPT" ]; then
        IOS_SIM_LINE=$("$RESOLVE_SCRIPT" || true)
        if [ -n "$IOS_SIM_LINE" ]; then
            IOS_SIM_ID=$(echo "$IOS_SIM_LINE" | cut -f1)
            IOS_SIM_NAME=$(echo "$IOS_SIM_LINE" | cut -f2)
            IOS_SIM_OS=$(echo "$IOS_SIM_LINE" | cut -f3)
        fi
    fi

    # Fallback when resolve script is unavailable: use name-based destination
    if [ -z "$IOS_SIM_ID" ] && [ -z "$IOS_SIM_NAME" ] && command -v xcrun >/dev/null 2>&1; then
        IOS_SIM_NAME="iPhone 16"
    fi

    # Helper: query the current state of a simulator by UDID
    sim_state() {
        xcrun simctl list devices -j 2>/dev/null \
            | python3 -c '
import sys, json
data = json.load(sys.stdin)
target_udid = sys.argv[1] if len(sys.argv) > 1 else ""
for devices in data.get("devices", {}).values():
    for d in devices:
        if d.get("udid") == target_udid:
            print(d.get("state", "Unknown"))
            sys.exit(0)
print("Unknown")
' "$1" 2>/dev/null || echo "Unknown"
    }

    # Helper: wait for a simulator to reach "Booted" state (polls every 2s, up to timeout)
    wait_for_sim_boot() {
        local udid="$1" timeout="${2:-30}" elapsed=0
        while [ "$elapsed" -lt "$timeout" ]; do
            if [ "$(sim_state "$udid")" = "Booted" ]; then
                return 0
            fi
            sleep 2
            elapsed=$((elapsed + 2))
        done
        return 1
    }

    IOS_HAS_SIM=false
    if [ -n "$IOS_SIM_ID" ]; then
        IOS_DEST="platform=iOS Simulator,id=$IOS_SIM_ID"
        IOS_HAS_SIM=true
        check_pass "iOS Simulator resolved ($IOS_SIM_NAME, $IOS_SIM_ID)"

        # Boot the simulator if not already booted, then wait until it's ready
        if [ "$(sim_state "$IOS_SIM_ID")" = "Booted" ]; then
            : # already booted, nothing to do
        else
            log_info "Booting simulator ($IOS_SIM_NAME)..."
            # boot may return non-zero if already booting/transitioning — that's OK
            xcrun simctl boot "$IOS_SIM_ID" 2>/dev/null || true
            if wait_for_sim_boot "$IOS_SIM_ID" 60; then
                check_pass "Simulator booted"
            else
                check_warn "Simulator did not reach Booted state within 60s — builds may fail"
            fi
        fi
    elif [ -n "$IOS_SIM_NAME" ]; then
        # Fallback: no UDID resolved (python3 missing), use name-based destination
        IOS_DEST="platform=iOS Simulator,name=$IOS_SIM_NAME"
        IOS_HAS_SIM=true
        check_pass "iOS Simulator destination ($IOS_SIM_NAME, name-based fallback)"
    else
        check_warn "No iOS Simulator found — skipping iOS build & tests"
    fi

    # Build (only if project exists and simulator is available)
    if [ "${SKIP_BUILD:-}" != "1" ] && [ "$IOS_HAS_SIM" = true ] && [ -d "$IOS/SerenadaiOS.xcodeproj" ] && command -v xcodebuild >/dev/null 2>&1; then
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
    elif [ "$IOS_HAS_SIM" != true ]; then
        check_skip "iOS build (no simulator)"
    fi

    # Tests (unit tests only — UI tests require a live server and have known flaky failures)
    if [ "${SKIP_TEST:-}" != "1" ] && [ "$IOS_HAS_SIM" = true ] && [ -d "$IOS/SerenadaiOS.xcodeproj" ] && command -v xcodebuild >/dev/null 2>&1; then
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
    elif [ "$IOS_HAS_SIM" != true ]; then
        check_skip "iOS tests (no simulator)"
    fi
fi

# ============================================================
# Section: Docker
# ============================================================
echo ""
echo -e "${BOLD}--- Docker ---${NC}"

if [ "${SKIP_DOCKER:-}" = "1" ]; then
    check_skip "Docker (SKIP_DOCKER=1)"
else
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
