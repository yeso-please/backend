# Agent instructions

## Source of truth

Start at `docs/README.md`. Product policy is `docs/product.md`, the API contract is `docs/api/`, calculation design is `docs/design/`. Work is tracked as GitHub issues (milestones `MVP`, `추가 기능`) that link to `docs/api/` sections. `docs/archive/` is superseded history and must not be used as a basis for implementation.

Before implementation, read the relevant `docs/conventions/`. If the contract must change, update `docs/api/` first, then change code, Flyway migration, and tests in the same PR. Do not commit or push unless a human asks.

## Repository skills

- Use `$tourapi-detail-backfill` when implementing, dry-running, or executing TourAPI attraction detail/image/course enrichment.
- Use `$flyway-rds-sync` whenever an entity, database constraint, index, migration, or RDS schema changes.

Do not expose credentials, automatically mutate production RDS, or treat generated text as source tourism data.
