---
id: ezcodemark-environment-action-legacy-random-revision-cas-loop
status: verified
scope: project
fingerprint: legacy-persisted-state-without-revision-generates-a-new-random-cas-token-on-every-read-and-can-never-be-upgraded
first_seen: 2026-07-31
last_verified: 2026-07-31
review_after: 2027-01-31
evidence:
  - schema-v1 stable migration revision regression
  - schema-v1 compare-and-write upgrade regression
  - 306-test current-tree suite
tags:
  - persistence
  - migration
  - cas
  - environment-actions
---

# Legacy State Random Revision CAS Loop

## Symptom

A schema-v1 Environment Actions file loads and normalizes in memory, but its first schema-v2 save repeatedly reports a concurrent revision conflict even when no other process is writing.

## Wrong Assumption

Treating a random default UUID created during deserialization as a usable migration revision for a file that never persisted that field.

## Verified Root Cause

Every read of a legacy file without `revision` constructed a different UUID. The Settings baseline therefore held revision A while the CAS read inside `compareAndWrite` observed revision B; each retry generated another value and could never match.

The store now derives a deterministic UUID from the exact legacy file content when the persisted revision is absent or invalid. The first successful CAS writes a normal schema-v2 successor with a fresh persisted revision. See [EnvironmentActionSettingsService.kt](../../../src/main/kotlin/emohce/data/environmentaction/EnvironmentActionSettingsService.kt#L333) and [EnvironmentActionSettingsStateTest.kt](../../../src/test/kotlin/emohce/data/environmentaction/EnvironmentActionSettingsStateTest.kt#L105).

## Detection Order

1. Read the same legacy file twice and compare the normalized revisions.
2. Attempt `compareAndWrite` with the first loaded revision and an edited successor.
3. Distinguish a real external write from a revision that changes solely because the file was reread.
4. Verify the upgraded file persists schema v2 and a new stable successor revision.

## Prevention Rule

Never use a nondeterministic in-memory default as the expected CAS token for persisted legacy content. A missing token must either fail closed or receive a deterministic content-bound migration identity until a successor is durably written.

## Latest Applicable Path

- Runtime: [EnvironmentActionSettingsService.kt](../../../src/main/kotlin/emohce/data/environmentaction/EnvironmentActionSettingsService.kt#L333)
- Regression: [EnvironmentActionSettingsStateTest.kt](../../../src/test/kotlin/emohce/data/environmentaction/EnvironmentActionSettingsStateTest.kt#L105)
- Acceptance: [verify.md](../../specs/260731/1720-environment-actions-codex-chat/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: a legacy or repairable persisted document lacks a valid revision but its exact bytes are available.
- Steps:
  1. Derive a stable namespaced identity from the exact persisted content.
  2. Use that identity consistently for every read of the unchanged legacy file.
  3. CAS-write the normalized successor against that identity.
  4. Persist a fresh ordinary revision in the successor and verify a restart reads it unchanged.
- Verification: repeated reads returned the same migration revision; the CAS upgrade succeeded and a second read observed schema v2 plus the edited content.
- Applicability boundary: migration/repair content without a valid persisted CAS token; ordinary schema-v2 writes still use random successor revisions.
- Fallback: reject the legacy file with an explicit migration error rather than retrying an unwinnable CAS loop.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-31 | Environment Actions / Codex Chat | Final source and migration review | Random deserialize default used as legacy CAS revision | Content-bound deterministic migration revision plus real write regression | Fixed; full 306-test suite passed |
