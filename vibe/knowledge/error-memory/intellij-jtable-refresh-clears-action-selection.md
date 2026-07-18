---
id: ezcodemark-intellij-jtable-refresh-clears-action-selection
status: verified
scope: project
fingerprint: intellij-jtable-fire-table-data-changed-clears-selected-row-before-action
first_seen: 2026-07-17
last_verified: 2026-07-17
review_after: 2027-01-17
evidence:
  - user-confirmed Profile toolbar edit and expected double-click editing did not open
  - sorted JTable toolbar and double-click integration regression
  - accepted 20-suite / 168-test gate
tags:
  - intellij-platform
  - swing
  - jtable
  - selection
  - settings
  - ui
---

# IntelliJ JTable Refresh Clears Action Selection

## Symptom

A selected Profile row appears actionable, but Toolbar Edit or row double-click silently does nothing. Other actions that resolve the selected row after synchronizing unrelated UI state can fail in the same way.

## Wrong Assumption

Assuming `fireTableDataChanged()` is a harmless repaint, or that `JTable.selectedRow` remains valid after a broad model event. A related wrong route is indexing domain data directly with a view row when sorting or filtering may be active.

## Verified Root Cause

The Profile edit path called [Reasoning synchronization](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L364-L367), which fired a full table-data change before the action read `selectedRow`. Swing correctly cleared selection, so the subsequent Profile lookup returned null and the action became a silent no-op. Direct view-row indexing would also target the wrong Profile under sorting.

The correction avoids a broad event for a value not displayed in the table, captures the stable Profile ID before other synchronization, explicitly hit-tests and selects a valid double-click row, and [converts between view and model indices](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L708-L727).

## Detection Order

1. Inject a recording editor and trigger the real ToolbarDecorator Edit action rather than a test-only direct edit helper.
2. Confirm whether any pre-action call fires `fireTableDataChanged`, `fireTableStructureChanged`, or replaces the model.
3. Assert selection immediately before and after the synchronization step.
4. Enable a sorter whose view order differs from model order and verify the exact Profile ID received by the editor.
5. Dispatch valid-row, blank-area, single-click, and right-click events and assert only a valid left double-click edits once.

## Prevention Rule

Capture a stable domain ID or convert and resolve the target before any broad JTable model event. Use row-specific events only when a displayed cell changed, avoid refresh events for hidden state, convert view/model indices in both directions, and make double-click actions hit-test the event row explicitly.

## Latest Applicable Path

- Double-click hit testing and shared edit route: [CommitProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L145-L153), [CommitProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L364-L392)
- Selection conversion: [CommitProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L708-L727)
- Sorted toolbar/double-click regression: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L959-L1036)
- Acceptance record: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: a JTable action depends on the currently selected domain row and state synchronization or sorting may occur.
- Steps:
  1. Resolve the target view row from the action or pointer and reject invalid rows.
  2. Convert the view row to a model row and capture a stable domain ID.
  3. Synchronize non-table state without firing a broad table event.
  4. Execute the action by stable ID; after a real data change, convert model back to view and restore selection.
  5. Test the actual toolbar/mouse entry with a sorter whose view and model orders differ.
- Verification: accepted r6 sorted ToolbarDecorator and mouse-event regression plus the 20-suite / 168-test full gate.
- Applicability boundary: IntelliJ/Swing tables whose actions resolve mutable domain rows from current selection.
- Fallback: if a full model replacement is unavoidable, capture the domain ID first and restore selection only after the model event completes.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17 | Commit message helper integration r6 | User could not open Profile editing and requested row double-click | full table refresh ran before selected-row resolution | remove hidden-state refresh, capture stable ID, hit-test double-click, convert sorted view/model rows | toolbar and double-click open and preserve the exact edited Profile |
