# Haitlify Architecture Transition

> Status: transition record. This document distinguishes the current implementation from planned work; it does not claim Hermes integration is complete.

## Implemented today

- The existing `apps/web` workspace, `server/platform` business backend, and `server/web-runner` Web automation Runner remain the supported product surfaces.
- Existing API/case workflows, business behavior, Runner execution, database migrations, and audit evidence remain in place.
- The former self-built testing Agent remains only as a compatibility path and is **deprecated**. It is not Hermes.

## Planned

- Migrate the AI/testing workspace toward a Hermes-backed runtime and integration boundary.
- Rebuild around Hermes without a Legacy fallback; remove old AI by dependency-aware batches, and validate business permissions, evidence and Runner handoff.
- Update historical design/acceptance records only with explicit current-status addenda; archival provenance must remain visible.

## Cleanup boundary

The rebranding covers tracked application code, backend package names, Runner references, root documentation, scripts, and workflows. The documentation cleanup is only one workstream; code cleanup and history auditing must be verified separately before completion is claimed. Untracked `AGENTS.md` and `docs/reviews/` are outside the cleanup scope. Existing business and Runner functionality is not removed as part of rebranding, and already-applied database migrations remain byte-for-byte unchanged.

Credential and environment files are outside this cleanup boundary. Synthetic examples may be used in documentation, but real credentials, tokens, cookies, personal data, and machine-specific paths must not be copied into tracked files.

## Compose retirement and isolated rebuild

The old root Compose file has been removed from Haitlify. Previous instructions to reuse the old Compose project, containers or storage are withdrawn. The new Hermes deployment and its enforced preflight are not implemented yet; see [deployment status](../DEPLOYMENT.md).

Old stopped containers and data volumes remain preserved outside this source cleanup. New services must use independent databases, volumes, networks and Hermes state paths; never infer permission to reconnect to old resources from the continued presence of those resources. Historical deployment documents are not current startup instructions.

## History and repository transition

The user has authorized retaining the old repository as the private `Haitlify-legacy` archive and creating a new `Haitlify` repository from a fresh snapshot with no inherited Git history. This repository transition is authorized but **not yet executed**.

No Git history rewrite is performed in this worktree. Existing commits, archived filenames, schema identifiers, database names, image/container labels, and compatibility strings can remain as provenance or runtime contracts. Remaining legacy strings should be reported and changed only when their compatibility impact is understood; this document is not evidence that every historical occurrence has been renamed.

### Editorial sanitization note

Archival documentation paths and identifiers were editorially sanitized from the former package namespace to `com.haitlify` for the new brand. This changes the presentation of historical references only; original execution evidence was not rerun as part of this documentation cleanup. The archival records remain historical evidence and must not be read as proof that Hermes integration is implemented.
