# PREFLIGHT

## Purpose

Confirm that the request can safely start with a published rule snapshot, a reachable Runner,
an explicit browser/viewport/base URL, and the requested source test points.

## Inputs

Run request summary; project and analysis-run references; selected point IDs; environment and
browser settings; login-discovery reference; immutable global and project rule snapshots.

## Allowed tools

Read persisted run/rule metadata, check Runner health/capabilities, and read the referenced
login-discovery status without copying credentials.

## Forbidden actions

Do not start model generation, collect or infer locators, persist credentials/Cookies/tokens,
execute a final test case, or replace an immutable rule snapshot.

## Pass conditions

All required references resolve, rules pass safety validation, Runner is available, and the
browser settings are supported. Emit a redacted readiness summary.

## Failure state

Set the run or affected point to `BLOCKED` for missing prerequisites and to `FAILED` for an
unexpected transport or persistence error. Include a concise user-visible reason.

## Visible event summary

`STATUS`: preflight started, readiness confirmed, or blocked with the missing prerequisite.
