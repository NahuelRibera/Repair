#!/usr/bin/env bash
# Dev-only: clears demo/QA Garage + maintenance + conversation data from the
# LOCAL Postgres instance, while preserving the motorcycle catalog and the
# entire knowledge base (manufacturers/models/documents/chunks/facts).
#
# NEVER run automatically by any script, hook, CI job, or application code
# path — this is a manual, opt-in developer convenience only, and it is
# deliberately hard to trigger by accident:
#
#   - Dry run (no args, or any invocation missing the flags below) only
#     connects read-only, prints the target and row counts, and exits
#     WITHOUT deleting anything.
#   - --yes alone is NOT enough to delete anything.
#   - Deletion requires BOTH --yes AND --confirm CLEAR-REPAIR-DEMO-DATA
#     (the exact phrase, case-sensitive) in the same invocation.
#   - Before deleting anything, a safety guard (scripts/lib/db-safety-guard.sh)
#     refuses to run at all unless the target is unambiguously this
#     project's own local dev database: host must be localhost/127.0.0.1,
#     the database name must be exactly "repair_v2", and it hard-refuses
#     the owner's separate car-prototype database ("repair_db") and any
#     name that merely looks production-like — see that file for the full
#     guard logic and scripts/clear-demo-data.guard.test.sh for its tests.
#
# What this deletes (only once ALL of the above are satisfied):
#   - app_users, garage_vehicles, maintenance_events, vehicle_preferences
#   - moto_chat_sessions, moto_chat_messages, moto_rag_runs, moto_retrieved_evidence
#   - the legacy anonymous-visitor car-prototype conversations
#     (diagnostic_sessions, diagnostic_messages, rag_runs, retrieved_evidence)
#
# What this NEVER touches:
#   - motorcycle_manufacturers, motorcycle_models, motorcycle_model_aliases
#   - motorcycle_knowledge_documents, motorcycle_knowledge_chunks
#   - motorcycle_facts (verified reference specs)
#   - the car catalogue tables (manufacturers/models/variants/specs)
#   - anything under data/ or knowledge/ on disk, or any other database
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib/db-safety-guard.sh
source "${SCRIPT_DIR}/lib/db-safety-guard.sh"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5544}"
DB_NAME="${DB_NAME:-repair_v2}"
DB_USER="${DB_USER:-repair_v2}"
DB_PASSWORD="${DB_PASSWORD:-repair_v2_local_only}"

CONFIRM_PHRASE="CLEAR-REPAIR-DEMO-DATA"

want_yes=0
confirm_value=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes)
      want_yes=1
      shift
      ;;
    --confirm)
      confirm_value="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

echo "Target: ${DB_NAME}@${DB_HOST}:${DB_PORT} (user: ${DB_USER})"

if ! is_safe_local_target "${DB_HOST}" "${DB_PORT}" "${DB_NAME}"; then
  echo
  echo "Refusing to proceed — this does not look like this project's local dev database." >&2
  exit 1
fi

count_query() {
  PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -t -A -c "
    SELECT 'app_users=' || (SELECT count(*) FROM app_users)
      || ', garage_vehicles=' || (SELECT count(*) FROM garage_vehicles)
      || ', maintenance_events=' || (SELECT count(*) FROM maintenance_events)
      || ', vehicle_preferences=' || (SELECT count(*) FROM vehicle_preferences)
      || ', moto_chat_sessions=' || (SELECT count(*) FROM moto_chat_sessions)
      || ', moto_chat_messages=' || (SELECT count(*) FROM moto_chat_messages)
      || ', moto_rag_runs=' || (SELECT count(*) FROM moto_rag_runs)
      || ', moto_retrieved_evidence=' || (SELECT count(*) FROM moto_retrieved_evidence)
      || ', diagnostic_sessions=' || (SELECT count(*) FROM diagnostic_sessions)
      || ', diagnostic_messages=' || (SELECT count(*) FROM diagnostic_messages)
      || ', rag_runs=' || (SELECT count(*) FROM rag_runs)
      || ', retrieved_evidence=' || (SELECT count(*) FROM retrieved_evidence);
  "
}

echo "Row counts that would be deleted:"
echo "  $(count_query)"
echo
echo "Catalog and knowledge base tables (motorcycle_*, manufacturers/models/"
echo "variants/specs) are never touched by this script."

if [[ "${want_yes}" -ne 1 || "${confirm_value}" != "${CONFIRM_PHRASE}" ]]; then
  cat <<EOF

This was a DRY RUN. Nothing was deleted.

To actually delete the rows above, re-run with BOTH:
  scripts/clear-demo-data.sh --yes --confirm ${CONFIRM_PHRASE}
EOF
  exit 0
fi

cat <<'EOF'

##############################################################
#                                                              #
#   DESTRUCTIVE ACTION: deleting the rows counted above now.   #
#   This cannot be undone.                                     #
#                                                              #
##############################################################
EOF
echo "Deleting from ${DB_NAME}@${DB_HOST}:${DB_PORT} ..."

PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- Motorcycle domain (dependency order: leaf tables first)
DELETE FROM moto_retrieved_evidence;
DELETE FROM moto_rag_runs;
DELETE FROM moto_chat_messages;
DELETE FROM moto_chat_sessions;
DELETE FROM maintenance_events;
DELETE FROM vehicle_preferences;
DELETE FROM garage_vehicles;
DELETE FROM app_users;

-- Legacy car prototype (anonymous visitor_id, preserved code path)
DELETE FROM retrieved_evidence;
DELETE FROM rag_runs;
DELETE FROM diagnostic_messages;
DELETE FROM diagnostic_sessions;

COMMIT;
SQL

echo "Done. Catalog and knowledge base are untouched."
