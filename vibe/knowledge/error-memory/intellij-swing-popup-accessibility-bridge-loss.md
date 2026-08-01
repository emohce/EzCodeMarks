---
id: ezcodemark-intellij-swing-popup-accessibility-bridge-loss
status: verified
scope: project
fingerprint: intellij-swing-combobox-popup-causes-computer-use-accessibility-tree-and-screenshot-loss-followed-by-blind-popup-retries
first_seen: 2026-08-01
last_verified: 2026-08-01
review_after: 2026-11-01
evidence:
  - runtime-receipt
tags:
  - intellij-platform
  - swing
  - accessibility
  - computer-use
  - combobox
---

# IntelliJ Swing Popup Can Collapse the Accessibility Bridge

## Symptom

Opening or selecting a Swing ComboBox popup caused both the accessibility tree and screenshot channel to disappear. Repeating popup interaction would have been blind and could not prove the selected value.

## Wrong Assumption

An accessibility popup failure is a transient state that should be retried through the same element and popup sequence until the tree returns.

## Verified Root Cause

The popup changed the native/Swing accessibility surface in a way the current Computer Use bridge could not observe. The previous element index was stale, and another equivalent popup action had no safe evidence channel.

## Correct Detection Order

1. Fetch one fresh state after the popup transition.
2. If both accessibility and screenshot evidence are unavailable, stop using the popup and invalidate every prior element index.
3. Restart the isolated host at most once when required for the remaining acceptance lane.
4. Use a bounded keyboard path or pre-seed the ignored fixture when that preserves the behavior being tested.
5. Verify the resulting persisted/visible value through a different page state and complete cleanup.

## Prevention Rule

Do not repeatedly click an unobservable Swing popup. The project [Computer Use route](../computer-use/jetbrains-plugin-smoke.md#computer-use-sequence) permits one materially different fallback, then stops the UI lane if evidence remains unavailable.

## Alternative Route

- Status: `verified`
- Preconditions: isolated disposable host; popup selection itself is not the sole behavior under test.
- Ordered steps: one post-popup observation -> invalidate stale indices -> restart once if needed -> keyboard or pre-seeded fixture -> verify through persisted/visible state -> cleanup.
- Verification: the smoke lane continued through a preconfigured local fake CLI and subsequently passed Configurable Apply/Cancel, navigation and runtime assertions without repeated blind popup calls.
- Applicability boundary: IntelliJ/Swing popup surfaces under the current macOS Computer Use bridge. If popup accessibility itself is the acceptance target, report the bridge limitation instead of substituting another path.
- Fallback: hand off the exact popup action to the user or mark the visual assertion unavailable.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Evidence | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-08-01 | Environment Actions / Codex Chat runtime lane | Swing ComboBox popup | repeated element/popup route would be blind | accessibility tree and screenshot both unavailable | invalidate indices, use isolated fixture and continue once | verified bounded recovery |
