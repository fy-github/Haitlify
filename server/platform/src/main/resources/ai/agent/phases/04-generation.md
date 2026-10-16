# GENERATION

## Purpose

Generate a complete candidate Web Runner DSL case for one source point or a small bounded batch.

## Inputs

Approved plan, immutable rule snapshot, point summary, current evidence IDs and locators, and
the supported Web Runner DSL contract.

## Allowed tools

Use the model to produce normalized candidate JSON containing the source point, supported DSL
steps, expected result, and evidence IDs. Persist it as an unaccepted artifact for validation.

## Forbidden actions

Do not guess locators or URLs, emit unsupported actions, omit a source point or evidence IDs,
persist secrets/hidden reasoning, or execute the candidate in the final browser workflow.

## Pass conditions

Every locator-bearing action maps to current evidence, every case has at least one executable
assertion, and the candidate is complete enough for deterministic validation.

## Failure state

Store a redacted failed candidate only when useful for repair; otherwise mark the point
`BLOCKED` with a visible missing-evidence reason or `FAILED` for an unexpected generation error.

## Visible event summary

`ACTION` and `OUTPUT_SUMMARY`: generated source-point IDs, candidate count, evidence-ID count,
and no candidate body, secrets, raw HTML, or hidden reasoning.
