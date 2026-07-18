---
id: ezcodemark-codex-machine-auth-generation-repair
status: verified
scope: project
fingerprint: invalid-or-missing-codex-auth-generation-is-randomized-in-memory-without-durable-cas-repair
first_seen: 2026-07-18
last_verified: 2026-07-18
review_after: 2027-01-18
evidence:
  - temporary-store repair and restart regressions
  - concurrent CAS-winner adoption regression
  - 259-test full suite
tags:
  - codex-app-server
  - persistence
  - consent
  - cas
---

# Codex Machine Auth Generation Repair

## Symptom

An invalid or missing persisted Codex auth generation can produce a different in-memory UUID in each IDE process or restart, repeatedly invalidating otherwise unchanged source-context consent.

## Wrong Assumption

Assuming in-memory normalization is sufficient for a generation that participates in durable consent identity and cross-process account coordination.

## Verified Root Cause

The prior loader replaced an invalid generation only in memory while retaining the original envelope revision. Later reads skipped the unchanged revision, and another process or restart generated a different UUID.

The current loader CAS-writes one successor, adopts a concurrent winner, preserves unknown JSON properties, retains the stored executable spelling, and rejects unsupported envelope schemas rather than downgrading them. See [CodexAppServerService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerService.kt#L358) and [CommitMessageStateAndCoordinatorTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/CommitMessageStateAndCoordinatorTest.kt#L409).

## Detection Order

1. Distinguish a missing/invalid generation from malformed JSON and unsupported envelope schemas.
2. Repair through compare-and-write against the loaded revision.
3. On mismatch, validate and adopt the winning envelope before retrying.
4. Preserve unknown payload fields and avoid persisting unrelated normalization.
5. Restart from the repaired store and confirm the generation and revision remain stable.

## Prevention Rule

Any recovered value used as durable identity must be persisted atomically; recovery must preserve forward-compatible fields and must not rewrite unsupported schemas.

## Latest Applicable Path

- Runtime: [CodexAppServerService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerService.kt#L358)
- Acceptance: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: the current machine envelope schema is supported and only `authGeneration` is missing or invalid.
- Steps:
  1. Parse the raw JSON object and typed current fields.
  2. Replace only `authGeneration` in the raw object.
  3. CAS-write a successor against the loaded revision.
  4. Adopt a valid concurrent winner or retry a still-invalid winner within the bounded attempt limit.
- Verification: missing, invalid, unknown-field, future-schema, restart, and CAS-winner tests passed.
- Applicability boundary: machine settings only; malformed JSON remains fail-closed.
- Fallback: report machine settings unavailable rather than use an unstable durable identity.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-18 | Commit message helper r7 P2 hardening | Residual persistence review | Random in-memory normalization | Forward-compatible CAS repair and winner adoption | Stable restart identity; all gates passed |
