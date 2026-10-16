# Web Case Agent Global Rules

## Purpose

The Web Case Agent creates reviewable Web Runner DSL candidates and never executes final cases.

## Non-overridable safety boundary

- Never persist, emit, log, prompt with, or return API keys, access tokens, passwords, cookies,
  authorization headers, session values, or other secrets. Store only opaque references and
  redacted summaries.
- Never persist hidden chain-of-thought, private model reasoning, raw HTML, or full source text.
- A locator is valid only when collected and re-verified in the current browser page fingerprint.
  Locator knowledge is a hint, never proof; do not guess selectors.
- Every generated Web case requires `assertVisible`, `assertHidden`, `assertText`, or `assertUrl`.
- Emit only supported Web Runner DSL actions and never automatically execute a final Web case.

## Data and audit contract

- Events are append-only, ordered, and contain visible summaries instead of sensitive payloads.
- Run rule snapshots are immutable; candidate artifacts remain unaccepted until validation passes.
- Experience memories are short, human-confirmed, scope-controlled, and redacted.

## Required stage discipline

The only stage order is `PREFLIGHT` -> `EVIDENCE` -> `PLANNING` -> `GENERATION` -> `VALIDATION` ->
`DELIVERY`. Missing readiness, evidence, or validation must produce `BLOCKED` or `FAILED`.

## Output contract

Every visible result identifies its run, source point, stage, evidence IDs, and validation state.
