---
id: ezcodemark-intellij-commit-toolbar-action-place-visibility
status: verified
scope: project
fingerprint: intellij-commit-action-hidden-setting-nonmodal-changesview-committoolbar-place
first_seen: 2026-07-15
last_verified: 2026-07-15
review_after: 2027-01-15
evidence:
  - live non-modal and traditional commit UI checks
  - CommitMessagePlatformIntegrationTest toolbar-place regression
tags:
  - intellij-platform
  - actions
  - vcs
  - ui
---

# IntelliJ Commit Toolbar Action-Place Visibility

## Symptom

An Action configured as hidden for the commit-message toolbar still appears disabled in IDEA's non-modal Commit ToolWindow, while it hides correctly in the traditional Commit Dialog.

## Wrong Assumption

Assuming every commit-message toolbar invocation uses Action place `CommitMessage`.

## Verified Root Cause

IDEA 2025.3 uses `ChangesView.CommitToolbar` for the non-modal Commit ToolWindow and `CommitMessage` for the traditional dialog. Visibility must recognize both places. Checking `event.isFromActionToolbar` prevents that place handling from suppressing Keymap, Find Action, or direct shortcut invocation.

See the [toolbar visibility update](../../../src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L49), its [place constants](../../../src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L232), and the [platform regression test](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L129).

## Detection Order

1. Inspect `AnActionEvent.place` in both commit surfaces.
2. Distinguish toolbar updates from Find Action/Keymap calls with `isFromActionToolbar`.
3. Test configured hidden and visible states for both toolbar places.
4. Confirm the same Action remains registered and invocable outside the toolbar.
5. Repeat a live smoke test in both commit UIs.

## Prevention Rule

For commit toolbar-only visibility, cover both public runtime places used by the supported IDE and gate the visibility override on an actual Action-toolbar invocation.

## Latest Applicable Path

- Visibility implementation: [CommitMessageActions.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L49)
- Place constants: [CommitMessageActions.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L232)
- Regression coverage: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L129)
- Live acceptance: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: one Action group is injected into VCS commit controls and toolbar visibility is user-configurable.
- Steps:
  1. Treat `CommitMessage` and `ChangesView.CommitToolbar` as the supported commit toolbar places.
  2. Apply hidden/visible state only when `isFromActionToolbar` is true.
  3. Keep Action registration and enabled-state logic independent from toolbar presentation.
  4. Test both places plus a non-toolbar invocation.
- Verification: fixture regression and live non-modal/traditional commit UI checks pass; hidden Format remains available from Find Action for a non-empty message.
- Applicability boundary: IDEA versions/surfaces currently supported by this plugin; inspect the runtime place again when adding a new commit UI.
- Fallback: if a future surface changes place, add that observed public place with a dedicated fixture and live check rather than broad substring matching.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-15 | Commit message helper integration | Non-modal Commit ToolWindow smoke test | `CommitMessage`-only visibility check | Dual-place toolbar-gated handling | Both commit surfaces and Find Action behavior verified |
