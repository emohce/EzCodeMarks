---
id: ezcodemark-llm-structured-repair-loses-confirmed-source-request
status: verified
scope: project
fingerprint: llm-structured-json-repair-invalid-output-without-confirmed-source-request
first_seen: 2026-07-16
last_verified: 2026-07-16
review_after: 2027-01-16
evidence:
  - r5 preview-refinement contract review
  - CommitMessagePlatformIntegrationTest exact-envelope and shared-budget regression
  - accepted 165-test suite and three-target Plugin Verifier
tags:
  - llm
  - structured-output
  - json-repair
  - prompt-confirmation
  - privacy
---

# LLM Structured Repair Loses Confirmed Source Request

## Symptom

The primary Commit-refinement request contains the current Commit, validated template, style, and user-confirmed instruction, but a fallback JSON-repair request contains only the invalid model response. The repair system prompt still describes the current Commit as the sole factual source even though that source is no longer present in the repair request.

## Wrong Assumption

Assuming an invalid structured response is always a sufficient factual anchor for a repair request, so the original user-confirmed request can be omitted without changing meaning or weakening prompt confirmation.

## Verified Root Cause

The repair helper originally rebuilt a new USER prompt from the invalid response and the one-off instruction. It preserved the request budget and system policy but dropped the exact confirmed source request, including the current Commit and template. The current implementation passes the prepared USER envelope into the repair helper and embeds it under `Confirmed source request` before the invalid response.

## Detection Order

1. Capture every Provider request for one operation that forces invalid structured output.
2. Compare the primary request with the repair request, not only their system prompts.
3. Verify the repair USER prompt contains the bounded current Commit, template, and confirmed instruction.
4. Verify it does not introduce the first Commit, Git status/diff/files/revision/history, credentials, or headers.
5. Confirm primary, repair, and any prior prompt-optimization request share the same request budget.

## Prevention Rule

When a structured LLM operation falls back to JSON repair, carry forward the exact bounded source request that the user confirmed. Append the invalid response as repair material; do not replace the source request with it. Reuse the same operation-local budget and system policy, and regression-test every emitted Provider request rather than only the final parsed result.

## Latest Applicable Path

- Prepared refinement and repair source propagation: [CommitMessageAiService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CommitMessageAiService.kt#L198)
- Repair USER prompt construction: [CommitMessageAiService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CommitMessageAiService.kt#L340)
- Exact-envelope, no-Git-context, and shared-budget regression: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L355)
- Acceptance record: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: a user-confirmed structured LLM request may use one controlled repair request after invalid JSON.
- Steps:
  1. Build one immutable primary prompt envelope and show it before sending.
  2. Retain its bounded USER prompt in the prepared operation object.
  3. On invalid output, construct repair from the confirmed USER prompt plus the bounded invalid response.
  4. Send repair with the same system policy and operation-local request budget.
  5. Reject invalid repaired output without writing the target document or history.
- Verification: the r5 regression forces prompt optimization, invalid Commit JSON, and repair; it asserts exact primary envelopes, repaired source retention, no Git-context headings or first-Commit marker, and exactly three budgeted Provider calls.
- Applicability boundary: structured LLM operations where the source request contains facts or user-confirmed instructions that must remain authoritative during repair.
- Fallback: if the repair source cannot be retained safely or shown under an explicit repair policy, disable automatic repair and require a newly confirmed user attempt.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-16 | Commit message helper integration r5 | Read-only contract review of the preview refinement fallback | repair used invalid output plus instruction but omitted the confirmed current-Commit/template request | carry forward the exact prepared USER prompt and add a three-request regression | current facts remain anchored; 165 tests and three compatible IDE targets pass |
