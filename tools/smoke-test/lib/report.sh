#!/usr/bin/env bash
# Per-pair results and summary output

set -euo pipefail

_REPORT_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$_REPORT_SH_DIR/common.sh"

# Arrays to accumulate results
declare -a SMOKE_RESULTS_PAIR=()
declare -a SMOKE_RESULTS_STATUS=()
declare -a SMOKE_RESULTS_TIME=()

report_result() {
    local pair="$1" status="$2" elapsed="$3"
    SMOKE_RESULTS_PAIR+=("$pair")
    SMOKE_RESULTS_STATUS+=("$status")
    SMOKE_RESULTS_TIME+=("$elapsed")

    if [ "$status" = "PASS" ]; then
        log_ok "$pair  PASS  (${elapsed}s)"
    elif [ "$status" = "SKIP" ]; then
        log_warn "$pair  SKIP"
    else
        log_error "$pair  FAIL  (${elapsed}s)"
    fi
}

print_summary() {
    local overall="PASS"

    echo ""
    echo "=========================================="
    echo " Smoke Test Results"
    echo "=========================================="

    for i in "${!SMOKE_RESULTS_PAIR[@]}"; do
        local pair="${SMOKE_RESULTS_PAIR[$i]}"
        local status="${SMOKE_RESULTS_STATUS[$i]}"
        local elapsed="${SMOKE_RESULTS_TIME[$i]}"

        if [ "$status" = "PASS" ]; then
            printf "  ${GREEN}%-20s PASS  (%ss)${NC}\n" "$pair" "$elapsed"
        elif [ "$status" = "SKIP" ]; then
            printf "  ${YELLOW}%-20s SKIP${NC}\n" "$pair"
        else
            printf "  ${RED}%-20s FAIL  (%ss)${NC}\n" "$pair" "$elapsed"
            overall="FAIL"
        fi
    done

    echo "=========================================="
    if [ "$overall" = "PASS" ]; then
        echo -e " Overall: ${GREEN}PASS${NC}"
    else
        echo -e " Overall: ${RED}FAIL${NC}"
    fi
    echo ""

    [ "$overall" = "PASS" ]
}
