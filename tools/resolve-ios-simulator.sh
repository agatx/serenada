#!/usr/bin/env bash
# Resolve the best available iPhone simulator for testing.
#
# Outputs a tab-separated line:  UDID\tname\tOS
#   e.g.  CA6FFD90-98C3-4580-BA33-11A912FC0BA9	iPhone 16	18.4
#
# Selection strategy:
#   1. Prefer booted simulators (avoids cold-boot delay)
#   2. Among equal boot state, pick the newest iOS runtime
#   3. Among equal runtime, pick the highest-numbered iPhone model
#
# Override: set IOS_SIMULATOR_DEST="name|OS" (e.g. "iPhone 16|18.4")
#           to skip auto-detection and use a specific simulator.
#
# Exits silently (no output) if no simulator is found.

set -euo pipefail

# Allow callers to override with a fixed "name|OS" pair.
if [ -n "${IOS_SIMULATOR_DEST:-}" ]; then
  SIM_NAME="${IOS_SIMULATOR_DEST%%|*}"
  SIM_OS="${IOS_SIMULATOR_DEST##*|}"
  # Resolve UDID for the overridden simulator.
  if command -v xcrun >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    SIM_UDID=$(xcrun simctl list devices available -j 2>/dev/null \
      | python3 -c "
import sys, json, re
data = json.load(sys.stdin)
target_name, target_os = sys.argv[1], sys.argv[2]
for runtime, devices in data.get('devices', {}).items():
    m = re.search(r'iOS[.-](\d+)[.-](\d+)', runtime)
    if not m:
        continue
    os_str = f'{m.group(1)}.{m.group(2)}'
    if os_str != target_os:
        continue
    for d in devices:
        if d.get('name') == target_name and d.get('isAvailable'):
            print(d['udid'])
            sys.exit(0)
" "$SIM_NAME" "$SIM_OS" 2>/dev/null || true)
    if [ -n "$SIM_UDID" ]; then
      printf '%s\t%s\t%s\n' "$SIM_UDID" "$SIM_NAME" "$SIM_OS"
      exit 0
    fi
  fi
  # UDID not resolved but name+OS known — print without UDID so caller
  # can fall back to name-based destination.
  printf '\t%s\t%s\n' "$SIM_NAME" "$SIM_OS"
  exit 0
fi

# Require xcrun and python3 for auto-detection.
if ! command -v xcrun >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  exit 0
fi

xcrun simctl list devices available -j 2>/dev/null \
  | python3 -c "
import sys, json, re

data = json.load(sys.stdin)
candidates = []
for runtime, devices in data.get('devices', {}).items():
    m = re.search(r'iOS[.-](\d+)[.-](\d+)', runtime)
    if not m:
        continue
    os_ver = (int(m.group(1)), int(m.group(2)))
    for d in devices:
        name = d.get('name', '')
        if not name.startswith('iPhone') or not d.get('isAvailable', False):
            continue
        booted = 1 if d.get('state') == 'Booted' else 0
        model_nums = [int(x) for x in re.findall(r'\d+', name)]
        # Sort key: booted first, then newest OS, then highest model number
        candidates.append((booted, os_ver, model_nums, d['udid'], name))

if not candidates:
    sys.exit(0)

best = max(candidates)
_, os_ver, _, udid, name = best
print(f'{udid}\t{name}\t{os_ver[0]}.{os_ver[1]}')
" 2>/dev/null || true
