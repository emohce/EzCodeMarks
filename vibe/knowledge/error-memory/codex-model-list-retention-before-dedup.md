---
id: ezcodemark-codex-model-list-retention-before-dedup
status: verified
scope: project
fingerprint: paginated-codex-model-list-retains-duplicate-heavy-responses-before-deduplication
first_seen: 2026-07-18
last_verified: 2026-07-18
review_after: 2027-01-18
evidence:
  - Codex fake-process pagination regressions
  - 259-test full suite
  - final r7 direct and canonical verifier matrices
tags:
  - codex-app-server
  - pagination
  - memory-bound
  - protocol
---

# Codex Model List Retention Before Deduplication

## Symptom

A duplicate-heavy paginated model response can retain every parsed model until the final page even though the returned list is deduplicated by model ID.

## Wrong Assumption

Treating page and item-count limits as sufficient retained-memory bounds when each protocol line and model field can still be large.

## Verified Root Cause

The client previously appended all parsed models to a list and called `distinctBy` only after pagination completed. Repeated IDs therefore consumed retained memory across pages. Long cursors were also stored without a dedicated cursor bound.

The current client uses a first-seen `LinkedHashMap`, counts raw parsed entries separately, bounds retained model text and cursor length, and rejects repeated cursors. See [CodexAppServerClient.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerClient.kt#L345) and [CodexAppServerClientTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/CodexAppServerClientTest.kt#L154).

## Detection Order

1. Check per-line, page-count, raw-entry, cursor, and retained aggregate bounds separately.
2. Verify duplicates are discarded before retained-size accounting.
3. Preserve first-seen ordering and first representation while rejecting repeated cursors.
4. Exercise multiple pages with duplicate representations whose combined size exceeds the retained budget.

## Prevention Rule

For bounded paginated protocols, deduplicate before retaining data and enforce a cumulative retained-size budget in addition to page and item counts.

## Latest Applicable Path

- Runtime: [CodexAppServerClient.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerClient.kt#L345)
- Acceptance: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: the protocol exposes a stable model identifier.
- Steps:
  1. Count every valid parsed entry against the raw-entry limit.
  2. Insert only the first occurrence of each identifier into an ordered map.
  3. Charge retained text only for inserted entries.
  4. Reject oversized or repeated continuation cursors.
- Verification: duplicate-page, cursor, aggregate, focused, full, and verifier gates passed.
- Applicability boundary: bounded in-memory model discovery; this does not replace the JSONL line limit.
- Fallback: fail the model-list request rather than retain an unbounded aggregate.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-18 | Commit message helper r7 P2 hardening | Residual resource-bound review | Deduplicate only after all pages | Early ordered deduplication plus cursor and aggregate bounds | Focused/full/verifier gates passed |
