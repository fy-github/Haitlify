# Haitlify Architecture Transition

> Status: transition record. This document distinguishes the current implementation from planned work; it does not claim Hermes integration is complete.

## Implemented today

- The existing `apps/web` workspace, `server/platform` business backend, and `server/web-runner` Web automation Runner remain the supported product surfaces.
- Existing API/case workflows, business behavior, Runner execution, database migrations, and audit evidence remain in place.
- The former self-built testing Agent remains only as a compatibility path and is **deprecated**. It is not Hermes.

## Planned

- Migrate the AI/testing workspace toward a Hermes-backed runtime and integration boundary.
- Revalidate the migrated workflow, permissions, evidence, and Runner handoff before changing the deprecated Agent status.
- Update historical design/acceptance records only with explicit current-status addenda; archival provenance must remain visible.

## Cleanup boundary

The rebranding covers tracked application code, backend package names, Runner references, root documentation, scripts, and workflows. The documentation cleanup is only one workstream; code cleanup and history auditing must be verified separately before completion is claimed. Untracked `AGENTS.md` and `docs/reviews/` are outside the cleanup scope. Existing business and Runner functionality is not removed as part of rebranding, and already-applied database migrations remain byte-for-byte unchanged.

Credential and environment files are outside this cleanup boundary. Synthetic examples may be used in documentation, but real credentials, tokens, cookies, personal data, and machine-specific paths must not be copied into tracked files.

## Compose and storage compatibility (migration-only note)

The following literal `cloudv4-api` references are intentionally retained **only** because they are the live Compose project identity required by an explicit migration procedure. The database name `ai_test_platform`, fixed container identities, and named volumes `mysql-data`, `redis-data`, and `minio-data` are intentionally retained. A checkout directory rename does not rename or migrate live Docker resources.

After moving the checkout, use:

```bash
COMPOSE_PROJECT_NAME=cloudv4-api docker compose -f /path/to/Haitlify/docker-compose.yml up -d
```

or pass `-p cloudv4-api` on every Compose command. Do not run `docker compose down -v` during the move. Back up first and verify the resolved project, containers, and volumes before any destructive operation. Do not copy this compatibility identity into new product names or unrelated documentation.

## History and repository transition

The user has authorized retaining the old repository as the private `Haitlify-legacy` archive and creating a new `Haitlify` repository from a fresh snapshot with no inherited Git history. This repository transition is authorized but **not yet executed**.

No Git history rewrite is performed in this worktree. Existing commits, archived filenames, schema identifiers, database names, image/container labels, and compatibility strings can remain as provenance or runtime contracts. Remaining legacy strings should be reported and changed only when their compatibility impact is understood; this document is not evidence that every historical occurrence has been renamed.

### Editorial sanitization note

Archival documentation paths and identifiers were editorially sanitized from the former package namespace to `com.haitlify` for the new brand. This changes the presentation of historical references only; original execution evidence was not rerun as part of this documentation cleanup. The archival records remain historical evidence and must not be read as proof that Hermes integration is implemented.
