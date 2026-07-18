---
id: ezcodemark-intellij-editable-combobox-live-filtering
status: verified
scope: project
fingerprint: intellij-editable-combobox-typing-does-not-filter-model-aqua-editor-replaced
first_seen: 2026-07-16
last_verified: 2026-07-17
review_after: 2027-01-17
evidence:
  - user-confirmed editable model input did not search/filter
  - user-confirmed fetched models could not be selected or confirmed
  - ModelSuggestionMatcher ranking tests
  - mounted IntelliJ/Aqua ComboBox, selection, confirmation, and late Fetch regressions
tags:
  - intellij-platform
  - swing
  - combobox
  - filtering
  - aqua
  - ui
---

# IntelliJ Editable ComboBox Live Filtering

## Symptom

The model field appears editable or reports loaded models, but the visible editor does not reliably accept search, popup selection does not persist, or the dialog cannot confirm the selected model. A naive off-screen test can manipulate a replacement editor that is not mounted in the actual IntelliJ/Aqua ComboBox.

## Wrong Assumption

Assuming Kotlin assignments to inherited `editor` / `isEditable` properties necessarily call the public Swing setters, or that restoring editor text alone is enough after rebuilding a ComboBox model. Related wrong routes are opening the popup whenever the component is merely showing and waiting for debounce before synchronizing typed text to `selectedItem`.

## Verified Root Cause

Editable selection, the mounted editor, suggestion filtering, and popup lifecycle are separate behaviors. The failed implementation assigned inherited Swing fields directly, cleared and rebuilt `DefaultComboBoxModel` without restoring `selectedItem`, and reopened the popup after selection-triggered document changes. The visible Aqua delegate could therefore retain a stale editor, while selection caused a query/model/popup feedback loop. A second race allowed opening the popup during the debounce window to restore the previous selected item over the new query.

The corrected implementation calls [the public editor/editable setters and synchronizes typed selection immediately](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ModelSuggestionMatcher.kt#L73-L109), uses deterministic [exact/prefix/substring/subsequence ranking](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ModelSuggestionMatcher.kt#L15-L61), preserves model selection/editor text/caret, opens suggestions only for a focused editor, stops debounce after a committed list choice, and [commits the exact current text before OK](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L170-L178). A late Fetch completion re-filters the current query instead of restoring the request-start text.

## Detection Order

1. Assert the configured editor component is a descendant of the actual ComboBox and compiled initialization invokes `setEditor` / `setEditable`.
2. Type a query and assert `selectedItem` changes immediately, before the debounce interval or popup opening.
3. After debounce, assert exact, prefix, middle, and sparse subsequence ranking against the backing model.
4. Select a non-first item from a long filtered list, wait beyond debounce, and assert selected item, editor text, and dialog result remain exact.
5. Assert unknown/custom text survives an empty match set and `OK`, and models returned after typing are filtered by the current query.
6. Recreate or reattach the editor/document and verify the listener still fires without duplicate listeners.

## Prevention Rule

Treat editable ComboBox search as an explicit state machine: mount editor/editable state through public setters, keep an immutable full suggestion list, synchronize typed text to selection immediately, debounce only ranking, update the existing model under a recursion guard, and restore selected/custom text plus caret. Reattach on editor/UI/document replacement, do not equate `isShowing` with an active search popup, and commit the editor before dialog acceptance.

## Latest Applicable Path

- Ranking and editable selector: [ModelSuggestionMatcher.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ModelSuggestionMatcher.kt#L15-L192)
- Profile dialog selection, OK, and late Fetch integration: [ProviderProfileDialog.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L94-L99), [ProviderProfileDialog.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L170-L178), [ProviderProfileDialog.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L287-L298)
- Mounted editor, long-list, custom-ID, and late Fetch regressions: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L1038-L1142)
- Acceptance record: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: a native IntelliJ editable ComboBox must support both Provider-supplied suggestions and arbitrary custom model IDs.
- Steps:
   1. Call public editor/editable setters and assert the editor is mounted in the ComboBox hierarchy.
   2. Preserve the complete distinct suggestion list separately from the visible model.
   3. Synchronize query selection immediately; debounce exact/prefix/substring/subsequence ranking only.
   4. Update the existing model under an adjustment guard and restore selected item, editor text, and caret.
   5. Suppress selection-triggered re-filter/reopen, re-filter late model responses, and commit editor text before OK.
   6. Reattach after editor/UI/document replacement and retain custom text when no suggestion matches.
- Verification: accepted r6 48-test focused provider gate, 20-suite / 168-test full gate, mounted-editor assertion, explicit setter bytecode inspection, and three-target Plugin Verifier.
- Applicability boundary: IntelliJ/Swing editable ComboBoxes that mix a suggestion list with custom values.
- Fallback: use a separate search field plus non-editable result list if the target look-and-feel cannot keep a stable editable ComboBox lifecycle.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-16 | Commit message helper integration r3 | User typed into the Model field expecting search/filter | editable selection UI had no document-driven model filtering | debounced ranked matcher, same-model updates, editor reattachment, Aqua fixture | typing filters suggestions and custom IDs remain valid |
| 2 | 2026-07-17 | Commit message helper integration r6 | User could not edit/search/select/confirm after models loaded | inherited Swing field assignment, null selection after model rebuild, unconditional popup reopening, and pre-debounce selection drift | public setters, immediate query selection, guarded model/popup state, explicit OK commit, late Fetch re-filtering, mounted/long-list fixtures | toolbar and custom/fetched model interactions pass focused/full and compatibility gates |
