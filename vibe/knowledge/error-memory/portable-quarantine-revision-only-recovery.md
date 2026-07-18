---
id: ezcodemark-portable-quarantine-revision-only-recovery
status: verified
scope: project
fingerprint: portable-settings-quarantine-skips-valid-replacement-when-content-changes-under-the-same-revision
first_seen: 2026-07-18
last_verified: 2026-07-18
review_after: 2027-01-18
evidence:
  - same-revision snapshot recovery regression
  - same-revision loadState recovery regression
  - 259-test full suite
tags:
  - portable-settings
  - quarantine
  - recovery
  - synchronization
---

# Portable Quarantine Revision-Only Recovery

## Symptom

A valid portable settings envelope can remain ignored until restart when it replaces malformed content but retains the quarantined revision.

## Wrong Assumption

Treating a revision as a complete fingerprint for externally replaced or repaired content.

## Verified Root Cause

Refresh and roaming reconciliation returned early whenever the current revision matched `quarantinedPortableRevision`. A manual or recovery rewrite with valid payload under the same revision was therefore never revalidated in the running process.

The current service validates every observed envelope, keeps invalid content write-blocking, and clears quarantine only after valid content passes normalization. See [CommitMessageSettingsService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CommitMessageSettingsService.kt#L391) and [CommitMessageStateAndCoordinatorTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/CommitMessageStateAndCoordinatorTest.kt#L382).

## Detection Order

1. Reproduce invalid content with a valid canonical envelope revision.
2. Confirm normal writes remain blocked while the invalid payload is present.
3. Replace the content with a valid payload under the same revision.
4. Exercise both snapshot refresh and `loadState` reconciliation without restarting.
5. Confirm failure state clears and subsequent writes succeed.

## Prevention Rule

Do not use a revision alone to skip validation after content quarantine unless the revision is cryptographically bound to the exact payload.

## Latest Applicable Path

- Runtime: [CommitMessageSettingsService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CommitMessageSettingsService.kt#L391)
- Acceptance: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: portable content can be replaced or repaired outside the normal successor writer.
- Steps:
  1. Read and validate the current envelope on refresh.
  2. Preserve the quarantine/write block on failure.
  3. Clear the failure only after the current payload validates.
  4. Reconcile the recovered envelope through the normal ancestry/CAS path.
- Verification: snapshot, `loadState`, subsequent-write, focused, and full regressions passed.
- Applicability boundary: same-revision external repair; normal writers still create successor revisions.
- Fallback: keep writes blocked and surface the compact store failure.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-18 | Commit message helper r7 P2 hardening | Residual recovery review | Revision-only quarantine skip | Revalidate current content on every refresh/reconcile | Same-process recovery and full gate passed |
