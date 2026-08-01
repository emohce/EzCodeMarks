---
id: ezcodemark-intellij-commit-editor-discovery
status: verified
scope: project
fingerprint: intellij-commit-toolwindow-visible-editor-data-context-focus-owned-by-plugin
first_seen: 2026-08-01
last_verified: 2026-08-01
review_after: 2027-02-01
evidence:
  - visible non-modal Commit editor incorrectly fell back to clipboard
  - focus-first plus visible-frame CommitMessageI implementation
  - real Replace flow changed only the native Commit message
tags:
  - intellij-platform
  - vcs
  - commit-toolwindow
  - data-context
  - focus
---

# Discover the Native Commit Editor Across Focused and Non-Modal Surfaces

## Symptom

`PREPARE_COMMIT` runs while the non-modal Commit ToolWindow and its message editor are already visible, but the plugin reports that the editor could not be opened and copies the prepared text to the clipboard.

## Wrong Assumption

Assuming the current focus DataContext will contain `VcsDataKeys.COMMIT_MESSAGE_CONTROL` after invoking `CheckinProject` from a different ToolWindow.

## Verified Root Cause

Focus remained in Environment Actions, so its DataContext had no Commit writer even though the native editor was visible elsewhere in the project frame. The launcher now preserves focus-DataContext discovery for traditional/modal Commit surfaces, then scans visible project-frame components for the public `CommitMessageI` adapter ([discovery](../../../src/main/kotlin/emohce/presentation/environmentaction/NativeCommitWorkflowLauncher.kt#L57-L66), [dual-path lookup](../../../src/main/kotlin/emohce/presentation/environmentaction/NativeCommitWorkflowLauncher.kt#L164-L187)). The non-modal regression proves hidden controls are ignored ([test](../../../src/test/kotlin/emohce/presentation/environmentaction/EnvironmentActionsDescriptorTest.kt#L136-L152)).

## Detection Order

1. Test with a non-empty message in the non-modal Commit ToolWindow while invoking from another ToolWindow.
2. Test the traditional/modal Commit dialog or a focused Commit editor to preserve DataContext behavior.
3. Distinguish “action opened” from “message control discovered”.
4. Require Replace/Append/Cancel for non-empty text and verify the native editor value afterward.
5. Assert no `git add`, `git commit`, staging or Commit-button invocation.

## Prevention Rule

Resolve the native Commit writer by current focus DataContext first, then a visible-component fallback for the project's non-modal Commit ToolWindow. Do not equate successful `CheckinProject` action completion with editor focus or message consumption.

## Latest Applicable Path

- Visible writer discovery: [NativeCommitWorkflowLauncher.kt](../../../src/main/kotlin/emohce/presentation/environmentaction/NativeCommitWorkflowLauncher.kt#L57-L66)
- Native workflow and dual lookup: [NativeCommitWorkflowLauncher.kt](../../../src/main/kotlin/emohce/presentation/environmentaction/NativeCommitWorkflowLauncher.kt#L118-L207)
- Discovery regression: [EnvironmentActionsDescriptorTest.kt](../../../src/test/kotlin/emohce/presentation/environmentaction/EnvironmentActionsDescriptorTest.kt#L136-L152)
- Runtime acceptance: [verify.md](../../specs/260731/1720-environment-actions-codex-chat/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: prepared text must enter whichever native Commit UI is already active without owning Git side effects.
- Steps:
  1. Read `COMMIT_MESSAGE_CONTROL` from the current focus DataContext.
  2. If absent, traverse only showing components in the project frame for `CommitMessageI`.
  3. Read current text through `CommitMessageUi` or the public commit-message document.
  4. Ask Replace/Append/Cancel for non-empty text; otherwise set directly.
  5. Use the one-use expiring provider only when no editor exists, then bounded clipboard fallback.
- Verification: IntelliJ 2026.1.4 displayed Replace/Append/Cancel and Replace changed `feat: smoke` to `feat: native prepared`; no Commit or staging action was invoked. Full tests and all three verifier targets pass.
- Applicability boundary: supported IntelliJ non-modal Commit ToolWindow and traditional/modal Commit surfaces.
- Fallback: retain the one-use provider and clipboard notice when no visible/focused native editor exists or the platform does not consume the draft.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-08-01 | Environment Actions / Codex Chat runtime acceptance | Run Prepare Commit from Environment Actions with Commit ToolWindow visible | focus-only writer lookup after `CheckinProject` | focus-first plus visible project-frame fallback | native non-modal editor receives the user-confirmed message |
