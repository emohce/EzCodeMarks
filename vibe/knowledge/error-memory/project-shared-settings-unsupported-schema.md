---
id: ezcodemark-project-shared-settings-unsupported-schema
status: verified
scope: project
fingerprint: CommitProjectSharedSettingsService-loadState-unsupported-schema-then-CommitProjectSharedConfigurable-reset-normalize-throws
first_seen: 2026-07-26
last_verified: 2026-07-26
review_after: 2027-01-26
evidence:
  - Settings screenshot showing Shared Project Configuration stuck on "Loading..."
  - Regression test `shared project settings page loads an error message for unsupported schema instead of crashing`
tags:
  - commit-message
  - settings
  - schema
  - persistent-state-component
---

# Project Shared Settings Unsupported Schema

## Symptom

The **Tools > EzCodeMarks > Git Commit Message > Shared Project Configuration** settings page stays stuck on "Loading..." or fails to open. The IDE log may show an `IllegalArgumentException` thrown from `CommitProjectSharedConfigurable.reset()` or `apply()`.

## Wrong Assumption

`CommitProjectSharedSettingsService.getState()` always returns a normalized, supported state, so callers can safely call `deepCopy().normalize()` on it.

## Verified Root Cause

`CommitProjectSharedSettingsService.loadState()` keeps the raw persisted state when `schemaVersion` is unsupported, but exposes it through `getState()`. `CommitProjectSharedConfigurable.reset()` and `apply()` called `service.state.deepCopy().apply { normalize() }`, which throws `IllegalArgumentException` for unsupported schemas, preventing `createComponent()` from finishing and leaving the settings panel on "Loading...".

The fix separates the raw persisted state (returned by `getState()` for serialization) from the normalized working state used by the UI. `CommitProjectSharedConfigurable` now consumes `service.snapshot()` and shows an explicit unsupported-schema message instead of crashing.

## Detection Order

1. Check whether `CommitProjectSharedSettingsService.isSchemaSupported()` is `true` before rendering editable shared-project controls.
2. If unsupported, return an explanatory panel and do not attempt to normalize the raw state.
3. Keep the raw state intact in `getState()` so a future-compatible IDE version can load and migrate it.
4. Never call `normalize()` on `getState()` from UI or business code; use `snapshot()` or service methods that already check support.

## Prevention Rule

Project-level `PersistentStateComponent` implementations must not expose a raw, potentially-unsupported state to UI code through `getState()`. Provide a `snapshot()` that returns the normalized working state and an explicit `isSchemaSupported()` check for graceful degradation.

## Latest Applicable Path

- Service: [CommitProjectSharedSettingsService.kt](../../../src/main/kotlin/emohce/data/commitmessage/CommitProjectSharedSettingsService.kt#L56)
- Configurable: [CommitProjectSharedConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectSharedConfigurable.kt#L89)
- Regression: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L989)

## Alternative Route

- Status: `verified`
- Steps:
  1. Open a project whose `.idea/ezCodeMarkCommitMessage.xml` contains a `schemaVersion` higher than `CommitProjectSharedSettingsState.CURRENT_SCHEMA_VERSION`.
  2. Open **Settings > Tools > EzCodeMarks > Git Commit Message > Shared Project Configuration**.
  3. Confirm the page shows a clear unsupported-schema message instead of staying on "Loading...".
- Verification: regression test passes; full `test`, `buildPlugin`, `verifyPluginProjectConfiguration`, and `verifyPluginStructure` pass.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-26 | Commit message helper post-r7 verification | Settings page stuck on Loading...; direct `normalize()` on raw unsupported state | Separate raw/serialized state from normalized snapshot; add unsupported-schema UI message | Shared project configurable loads safely and preserves raw state for future migration | Fixed and regression-tested |
