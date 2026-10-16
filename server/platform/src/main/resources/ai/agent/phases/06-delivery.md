# DELIVERY

## Purpose

Persist accepted artifacts, append them idempotently to the reviewed draft list, summarize the
run, and create only a human-reviewable memory candidate.

## Inputs

Accepted artifacts with source points/evidence IDs, blocked/failed point summaries, immutable
rule version, and deterministic validation outcomes.

## Allowed tools

Append accepted cases by artifact ID, persist output summaries, create a short pending memory
candidate, and emit ordered redacted delivery events.

## Forbidden actions

Do not deliver unaccepted candidates, overwrite existing accepted cases, activate/share a memory
without human confirmation, store secrets/hidden reasoning, or automatically execute final Web
cases.

## Pass conditions

Every delivered case is accepted, traceable to a source point and evidence IDs, appended
idempotently, and represented in the run summary. Memory candidates remain `PENDING`.

## Failure state

Preserve accepted artifacts and mark remaining undeliverable points `BLOCKED` or `FAILED` with a
visible reason. A partial delivery must never be reported as fully successful.

## Visible event summary

`OUTPUT_SUMMARY` and `STATUS`: delivered/blocked/failed counts, artifact IDs, run terminal state,
and review-required memory-candidate count.
