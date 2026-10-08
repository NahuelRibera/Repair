#!/usr/bin/env bash
# Full verification suite for an agent patch (run by the secret-free `verify` job).
set -euo pipefail

# DB_PORT defaults to the project's 5544; override it to run next to a local dev database.
export DB_PORT="${DB_PORT:-5544}"
DB_CONTAINER="repair-ci-db-$$"
trap 'docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true' EXIT

echo "::group::Postgres + pgvector for pipeline integration tests"
docker run -d --name "$DB_CONTAINER" -p "127.0.0.1:${DB_PORT}:5432" \
  -e POSTGRES_DB=repair_v2 -e POSTGRES_USER=repair_v2 -e POSTGRES_PASSWORD=repair_v2_local_only \
  pgvector/pgvector:pg17 >/dev/null
# The image restarts once after initialisation: wait for the second "ready" message.
for _ in $(seq 1 60); do
  [ "$(docker logs "$DB_CONTAINER" 2>&1 | grep -c 'ready to accept connections')" -ge 2 ] && break; sleep 1
done
docker exec "$DB_CONTAINER" pg_isready -U repair_v2 -d repair_v2
docker exec -i "$DB_CONTAINER" psql -U repair_v2 -d repair_v2 -v ON_ERROR_STOP=1 \
  < apps/api/src/main/resources/db/migration/V1__extensions.sql
echo "::endgroup::"

echo "::group::Java (unit + Testcontainers integration tests)"
(cd apps/api && ./mvnw -B -ntp test)
echo "::endgroup::"

echo "::group::Python pipelines"
python -m pip install -q -e "pipelines[dev]"
(cd pipelines && pytest -q)
echo "::endgroup::"

echo "::group::Web"
(cd apps/web && npm ci --no-audit --no-fund && npm run lint && npm run typecheck && npm run build)
echo "::endgroup::"
