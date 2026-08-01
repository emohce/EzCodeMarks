---
id: ezcodemark-intellij-toolwindow-inline-approval
status: verified
scope: project
fingerprint: intellij-toolwindow-appserver-approval-messages-showdialog-hidden-edt-block
first_seen: 2026-08-01
last_verified: 2026-08-01
review_after: 2027-02-01
evidence:
  - fake App Server approval runtime in IntelliJ 2026.1.4
  - EDT stack ending in Messages.showDialog and Dialog.show
  - inline Deny and subsequent turn completion runtime evidence
tags:
  - intellij-platform
  - toolwindow
  - approval
  - modality
  - edt
  - codex-app-server
---

# IntelliJ ToolWindow Approvals Must Remain Inline and Stoppable

## Symptom

Codex Chat enters `awaitingApproval`, but no operable approval dialog is visible and the ToolWindow Stop control cannot respond. The EDT is blocked in `Messages.showDialog`/`Dialog.show` while the long-running turn remains active.

## Wrong Assumption

Assuming a blocking modal dialog is an acceptable approval surface for an asynchronous ToolWindow session and will always be raised above the IDE window.

## Verified Root Cause

The approval event arrived asynchronously while the ToolWindow owned the workflow. A blocking `Messages.showDialog` nested the EDT and could be hidden or inaccessible in the real 2026.1 host, so the user could neither decide nor stop the turn. Approval is now a redacted, queued inline panel with explicit Allow/Deny buttons ([layout](../../../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L55-L66), [event handling](../../../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L373-L417)). Stop, New conversation, completion, failure and disposal clear that state.

## Detection Order

1. Drive a fake App Server to emit a real approval request while a turn is active.
2. Confirm the approval controls, Stop button and transcript are simultaneously visible and operable.
3. If the UI freezes, capture the EDT stack before changing protocol code.
4. Reject and allow in separate turns; verify one response per token and continued session progress.
5. Interrupt and dispose with approval pending; verify process-tree closure.

## Prevention Rule

Long-running ToolWindow approvals must be non-blocking, inline, visibly associated with the active turn, redacted and explicitly user-driven. Never hold the EDT in a modal approval dialog; Stop and lifecycle disposal must remain operable while approval is pending.

## Latest Applicable Path

- Inline approval layout and controls: [EnvironmentActionsPanel.kt](../../../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L55-L66)
- Approval queue and response: [EnvironmentActionsPanel.kt](../../../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L373-L417)
- Modal-dialog regression guard: [EnvironmentActionsDescriptorTest.kt](../../../src/test/kotlin/emohce/presentation/environmentaction/EnvironmentActionsDescriptorTest.kt#L75-L96)
- Runtime acceptance: [verify.md](../../specs/260731/1720-environment-actions-codex-chat/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: an asynchronous ToolWindow session can pause for one or more user approvals.
- Steps:
  1. Queue bounded redacted approval summaries by protocol token.
  2. Render one active request inline with explicit Allow/Deny.
  3. Keep Stop enabled and Send disabled for the active turn.
  4. Clear approval state on response, interrupt, new conversation, completion, failure and disposal.
  5. Verify approval and interrupt against a fake protocol process in a real IDE host.
- Verification: IntelliJ 2026.1.4 displayed the inline command summary and on-screen buttons; Deny produced `approval result: decline`, completed the turn and re-enabled Send. A following slow turn interrupted successfully.
- Applicability boundary: ToolWindow or editor-side long-running workflows where approvals arrive asynchronously.
- Fallback: use a platform non-modal banner/notification with explicit actions only if it preserves the same active-turn and Stop semantics.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-08-01 | Environment Actions / Codex Chat runtime acceptance | Fake App Server emitted a local-command approval | blocking `Messages.showDialog` from the ToolWindow event | inline queued approval panel plus lifecycle clearing | Deny, Stop and subsequent turns remain operable |
