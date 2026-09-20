#!/usr/bin/env bash
# Unit tests for scripts/lib/db-safety-guard.sh — pure logic, no database,
# no network. Run directly:
#   bash scripts/clear-demo-data.guard.test.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/db-safety-guard.sh"

failures=0
total=0

assert_pass() {
  local desc="$1" host="$2" port="$3" dbname="$4"
  total=$((total + 1))
  if is_safe_local_target "$host" "$port" "$dbname" >/dev/null 2>&1; then
    echo "ok   - $desc"
  else
    echo "FAIL - $desc (expected ALLOW, got REFUSE)"
    failures=$((failures + 1))
  fi
}

assert_fail() {
  local desc="$1" host="$2" port="$3" dbname="$4"
  total=$((total + 1))
  if is_safe_local_target "$host" "$port" "$dbname" >/dev/null 2>&1; then
    echo "FAIL - $desc (expected REFUSE, got ALLOW)"
    failures=$((failures + 1))
  else
    echo "ok   - $desc"
  fi
}

# --- Expected to be allowed ---
assert_pass "localhost + expected port + repair_v2"        "localhost" "5544" "repair_v2"
assert_pass "127.0.0.1 + expected port + repair_v2"        "127.0.0.1" "5544" "repair_v2"
assert_pass "localhost + a different local port + repair_v2" "localhost" "5433" "repair_v2"

# --- Expected to be refused: not a local host ---
assert_fail "remote hostname"                    "db.example.com" "5544" "repair_v2"
assert_fail "a cloud provider RDS-style host"    "repair-v2-prod.abcdefghijk.us-east-1.rds.amazonaws.com" "5432" "repair_v2"
assert_fail "0.0.0.0 is not a valid local target" "0.0.0.0" "5544" "repair_v2"
assert_fail "empty host"                          "" "5544" "repair_v2"

# --- Expected to be refused: protected / production-like database names ---
assert_fail "the owner's original car-prototype DB, even on localhost" "localhost" "5544" "repair_db"
assert_fail "a database literally named production"                    "localhost" "5544" "production"
assert_fail "a database name containing prod"                          "localhost" "5544" "repair_v2_prod"
assert_fail "case-insensitive PROD match"                               "localhost" "5544" "REPAIR_V2_PROD"
assert_fail "an unrelated/unknown local database name"                  "localhost" "5544" "some_other_db"
assert_fail "empty database name"                                       "localhost" "5544" ""

# --- Expected to be refused: malformed port ---
assert_fail "non-numeric port"   "localhost" "abc" "repair_v2"
assert_fail "empty port"         "localhost" "" "repair_v2"

echo
echo "${total} checks, ${failures} failed"
if [[ "${failures}" -gt 0 ]]; then
  exit 1
fi
echo "All guard checks passed."
