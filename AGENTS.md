# Agent instructions

## Source of truth

MVP work starts at `docs/mvp/README.md`. Files `docs/FEATURE-SPEC.md`, `docs/API-DESIGN-DRAFT.md`, and `docs/BUTTON-SPEC.md` are legacy demo-analysis notes and must not override `docs/mvp/decisions.md`.

Before implementation, read the relevant `docs/conventions/`, create or update `docs/features/{feature}.md` and `docs/api/{domain}.md`, then change code, Flyway migration, and tests in the same PR.

## Repository skills

- Use `$tourapi-detail-backfill` when implementing, dry-running, or executing TourAPI attraction detail/image/course enrichment.
- Use `$flyway-rds-sync` whenever an entity, database constraint, index, migration, or RDS schema changes.

Do not expose credentials, automatically mutate production RDS, or treat generated text as source tourism data.
