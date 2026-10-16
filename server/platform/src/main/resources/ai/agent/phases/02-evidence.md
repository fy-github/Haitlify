# EVIDENCE

## Purpose

Collect current-browser page evidence that can whitelist locator-bearing actions for later
planning, generation, and validation.

## Inputs

Validated run context, page intent, current base URL/browser/viewport, and locator knowledge
hints. Knowledge hints never replace live browser evidence.

## Allowed tools

Ask the model for page intent only; request browser locator discovery; compare page fingerprint;
record evidence IDs, normalized locators, accessible names, roles, visibility, and collection time.

## Forbidden actions

Do not generate final DSL, guess a selector, reuse a stale fingerprint, send secrets to a model,
capture raw page HTML, or execute the final Web case.

## Pass conditions

The current browser returns a non-empty, supported locator evidence set with a page fingerprint;
every retained locator is re-queryable on that same page.

## Failure state

Set the affected point to `BLOCKED` when the page is unavailable, no evidence is found, or the
fingerprint changed. Mark stale knowledge as stale rather than silently reusing it.

## Visible event summary

`TOOL_RESULT`: evidence collection outcome, page fingerprint summary, evidence count, and any
blocking reason; never include page HTML, credentials, or Cookies.
