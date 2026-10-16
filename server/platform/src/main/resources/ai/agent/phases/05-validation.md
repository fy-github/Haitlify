# VALIDATION

## Purpose

Deterministically normalize and validate each candidate against the Runner DSL, evidence
whitelist, assertion requirement, schema, coverage, and duplicate-artifact rules.

## Inputs

Unaccepted candidate artifact, source point ID, current evidence whitelist/page URLs, rule
snapshot, and previously accepted artifact IDs.

## Allowed tools

Run server-side schema/DSL checks, evidence and URL whitelist checks, assertion/coverage checks,
and bounded repair prompts containing only normalized candidate summaries and exact errors.

## Forbidden actions

Do not accept guessed selectors, silently repair beyond the configured limit, execute a final
case, expose secrets or hidden reasoning, or overwrite an already accepted artifact.

## Pass conditions

Candidate uses only supported DSL, references current evidence, contains at least one executable
assertion, has no deterministic errors, and is marked `accepted=true` exactly once.

## Failure state

Keep validation errors on the artifact. After the bounded repair limit, mark the point `BLOCKED`;
use `FAILED` only for unexpected validator, model, or persistence faults.

## Visible event summary

`VALIDATION`: source point, pass/fail, repair round, error codes/count, and acceptance state;
never include sensitive values or a hidden model trace.
