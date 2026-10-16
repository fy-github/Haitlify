# PLANNING

## Purpose

Create one evidence-grounded test intent per source point before any final Web DSL is requested.

## Inputs

Immutable rule snapshot, source-point summary, current evidence IDs, page URL/fingerprint, and
applicable active human-confirmed memories.

## Allowed tools

Use the model for concise objective, expected outcome, coverage, and evidence-ID selection;
read active scoped memories and persisted evidence summaries.

## Forbidden actions

Do not output locators, final DSL, credentials, raw source documents, hidden reasoning, or a
final browser execution request. Do not select an evidence ID absent from the current page.

## Pass conditions

Each plan names its source point, intended outcome, coverage target, and current evidence IDs.
The plan is sufficient for deterministic generation but contains no executable selector syntax.

## Failure state

Set the point to `BLOCKED` when required evidence or an unambiguous expected outcome is missing;
set it to `FAILED` only for unexpected model or persistence failure.

## Visible event summary

`OUTPUT_SUMMARY`: planned-point count, evidence-reference count, and concise blocked reasons.
