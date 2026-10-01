# Agent instructions

## Source of truth

Start at `docs/README.md`. Product policy is `docs/product.md`, the API contract is `docs/api/`, calculation design is `docs/design/`. Work is tracked as GitHub issues (milestones `MVP`, `추가 기능`) that link to `docs/api/` sections. `docs/archive/` is superseded history and must not be used as a basis for implementation.

Before implementation, read the relevant `docs/conventions/`. If the contract must change, update `docs/api/` first, then change code, Flyway migration, and tests in the same PR. Do not commit or push unless a human asks.

## Repository skills

- Use `$tourapi-attraction-backfill` (legacy alias: `$tourapi-detail-backfill`) to automatically collect and write eligible missing TourAPI attraction descriptions to the configured development RDS. Invoking the skill authorizes this bounded dev-only API/DML operation; it never authorizes production, schema changes, course ingestion, or image ingestion. Keep its target, TLS, API-response, quota, and writer-lock fail-closed checks. Team setup and run instructions: `docs/runbooks/tourapi-attraction-backfill.md`.
- Use `$flyway-rds-sync` whenever an entity, database constraint, index, migration, or RDS schema changes.
- Use `$db-man` to coordinate the full entity → Flyway → local/Testcontainers → explicitly requested dev RDS lifecycle for schema changes. Apply `$flyway-rds-sync` alongside it for migration mechanics; neither skill authorizes an unrequested RDS write.
- Use `$tripin-dev-run` to start the Spring backend, the `ai` embedding server, and `test_frontend` together on a local machine with Docker Compose (`compose.dev.yaml`; dev RDS by default, `compose.dev.local-db.yaml` for local PostgreSQL). It checks the dev RDS target and pending Flyway migrations first; starting the backend never authorizes an unrequested shared-RDS migration.

Do not expose credentials, automatically mutate production RDS, or treat generated text as source tourism data.
