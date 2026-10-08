#!/usr/bin/env bash
# Full verification suite for an agent patch (run by the secret-free `verify` job).
set -euo pipefail

echo "::group::Postgres + pgvector for pipeline integration tests"
docker run -d --name repair-ci-db -p 5544:5432 \
  -e POSTGRES_DB=repair_v2 -e POSTGRES_USER=repair_v2 -e POSTGRES_PASSWORD=repair_v2_local_only \
  pgvector/pgvector:pg17 >/dev/null
for _ in $(seq 1 30); do docker exec repair-ci-db pg_isready -U repair_v2 -d repair_v2 >/dev/null 2>&1 && break; sleep 2; done
docker exec -i repair-ci-db psql -U repair_v2 -d repair_v2 -v ON_ERROR_STOP=1 \
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
(cd apps/web && npm ci --no-audit --no-fund && npx tsc --noEmit && npm run build)
# Lint is advisory until the existing react-hooks errors are fixed (see ci.yml web-lint).
(cd apps/web && npm run lint) || echo "::warning::npm run lint reported problems"

echo "::endgroup::"
