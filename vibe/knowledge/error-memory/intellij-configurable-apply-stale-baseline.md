---
id: ezcodemark-intellij-configurable-apply-stale-baseline
status: verified
scope: project
fingerprint: configurable-three-way-apply-marks-stale-controls-clean-without-resnapshotting-preserved-external-fields
first_seen: 2026-07-18
last_verified: 2026-07-18
review_after: 2027-01-18
evidence:
  - project-defaults disjoint external-change regression
  - project configurable conflict regressions
  - 259-test full suite
tags:
  - intellij-platform
  - configurable
  - optimistic-merge
  - settings
---

# IntelliJ Configurable Apply Stale Baseline

## Symptom

A Settings page can report clean after Apply while still displaying an old value that its three-way merge intentionally preserved from an external change.

## Wrong Assumption

Assuming copied working values are always the new loaded baseline after a partial optimistic merge.

## Verified Root Cause

Project Defaults applied only locally changed fields, correctly preserving disjoint external edits, but then copied stale controls into its loaded baseline. The visible template could therefore disagree with persisted state while `isModified()` returned false.

The page now calls `reset()` after successful Apply, reloading persisted IDs and current definition choices. Conflicts still throw before writes or reset, so local edits remain staged. See [CommitProjectDefaultsConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectDefaultsConfigurable.kt#L72) and [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L1685).

## Detection Order

1. Open the page with baseline values A/X.
2. Change an unrelated persisted field externally to B while editing X to Y locally.
3. Apply and compare persisted state, visible controls, and `isModified()`.
4. Separately verify same-field conflicts preserve external state and keep local controls modified.

## Prevention Rule

After a successful partial or three-way Settings Apply, rebuild the loaded baseline from persisted state rather than assuming the pre-merge controls are authoritative.

## Latest Applicable Path

- Runtime: [CommitProjectDefaultsConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectDefaultsConfigurable.kt#L72)
- Acceptance: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: the page applies selected fields while preserving disjoint external changes.
- Steps:
  1. Reject same-field conflicts before mutation.
  2. Apply only locally changed fields and requested side effects.
  3. Resnapshot all controls and loaded baselines from persisted state.
  4. Leave conflict paths untouched so unsaved edits remain visible.
- Verification: disjoint merge plus Defaults, Shared, and Project Providers conflict tests passed.
- Applicability boundary: Settings pages with optimistic or partial merge behavior.
- Fallback: reject the Apply rather than mark stale controls clean.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-18 | Commit message helper r7 P2 hardening | Residual Settings lifecycle review | Manual baseline assignment after partial merge | Post-Apply persisted-state resnapshot | Platform and full gates passed |
