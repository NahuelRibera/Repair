#!/usr/bin/env bash
# Pure safety-check logic for scripts/clear-demo-data.sh, split into its own
# sourceable file so it can be unit-tested (see clear-demo-data.guard.test.sh)
# without ever touching a real database.
#
# is_safe_local_target HOST PORT DBNAME
#   Prints a reason to stderr and returns 1 if the target is not clearly
#   this project's own local development database. Returns 0 only for a
#   target that is unambiguously safe to run a destructive script against.

# Never let this script (or its guard) touch the owner's separate, original
# car-prototype database — see CLAUDE.md "Never touch the owner's original
# local repair_db PostgreSQL database."
HARD_DENIED_DB_NAMES=("repair_db")

# The one database name this script actually knows how to safely clear.
ALLOWED_DB_NAMES=("repair_v2")

# Hosts that are unambiguously "this machine, right now" — never a remote
# or shared database reachable by that name.
ALLOWED_HOSTS=("localhost" "127.0.0.1")

is_safe_local_target() {
  local host="$1" port="$2" dbname="$3"

  if [[ -z "$host" || -z "$port" || -z "$dbname" ]]; then
    echo "REFUSED: host, port, and database name must all be set." >&2
    return 1
  fi

  if ! [[ "$port" =~ ^[0-9]+$ ]]; then
    echo "REFUSED: port '$port' is not numeric." >&2
    return 1
  fi

  local host_ok=0
  for allowed in "${ALLOWED_HOSTS[@]}"; do
    if [[ "$host" == "$allowed" ]]; then
      host_ok=1
      break
    fi
  done
  if [[ "$host_ok" -ne 1 ]]; then
    echo "REFUSED: host '$host' is not a recognized local host (allowed: ${ALLOWED_HOSTS[*]})." >&2
    echo "         This script never runs against a remote or shared database." >&2
    return 1
  fi

  local dbname_lower
  dbname_lower="$(printf '%s' "$dbname" | tr '[:upper:]' '[:lower:]')"

  for denied in "${HARD_DENIED_DB_NAMES[@]}"; do
    if [[ "$dbname_lower" == "$denied" ]]; then
      echo "REFUSED: '$dbname' is a protected database name and is never touched by this script." >&2
      return 1
    fi
  done

  if [[ "$dbname_lower" == *prod* ]]; then
    echo "REFUSED: database name '$dbname' looks production-like (contains 'prod')." >&2
    return 1
  fi

  local dbname_ok=0
  for allowed in "${ALLOWED_DB_NAMES[@]}"; do
    if [[ "$dbname" == "$allowed" ]]; then
      dbname_ok=1
      break
    fi
  done
  if [[ "$dbname_ok" -ne 1 ]]; then
    echo "REFUSED: '$dbname' is not this project's known local dev database (expected: ${ALLOWED_DB_NAMES[*]})." >&2
    return 1
  fi

  return 0
}
