---
id: ezcodemark-intellij-modal-settings-background-callback-modality
status: verified
scope: project
fingerprint: intellij-settings-dialog-background-callback-never-visible-default-modality
first_seen: 2026-07-16
last_verified: 2026-07-16
review_after: 2027-01-16
evidence:
  - user-confirmed Fetch Models and Test connection no-response symptoms
  - CommitMessagePlatformIntegrationTest queued/in-flight/cancel lifecycle regressions
  - accepted r3 152-test IntelliJ fixture suite
tags:
  - intellij-platform
  - settings
  - dialog-wrapper
  - modality
  - background-task
  - cancellation
---

# IntelliJ Modal Settings Background Callback Modality

## Symptom

Fetch Models or Test connection starts from an IntelliJ Settings/Profile dialog, but its status and result never become visible while the dialog remains open. The late callback may run only after closing Settings, at which point dispose or stale-request guards correctly discard it and the operation appears to have done nothing.

## Wrong Assumption

Assuming a background task can always post its result with the default `Application.invokeLater` modality and still update a modal `Configurable` or `DialogWrapper` immediately.

## Verified Root Cause

The default callback is scheduled for a non-modal context. When Settings or a Profile `DialogWrapper` owns the active modality, that callback waits behind the modal UI. Closing the UI changes request/dispose state before delivery, so a correct stale guard makes the starved callback disappear.

The Provider settings now route UI completion through a [modal-compatible EDT helper](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L735) and pair each Test with a [request-local handle and explicit cancellation checks](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L527). Fetch Models similarly returns an explicit request handle to the [Profile dialog lifecycle](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L238).

## Detection Order

1. Confirm the Provider call completes or reaches a mock callback while Settings remains open.
2. Inspect the EDT handoff for default/non-modal `invokeLater` usage.
3. Verify the callback runs under the active Settings/Dialog modality without closing the dialog.
4. Cancel before queue execution, during Provider work, after replacement, and during dispose/reset/apply.
5. Verify a late result cannot change status, suggestions, or the active Profile.

## Prevention Rule

For background operations launched from modal IntelliJ settings/dialog UI, post results with an explicitly compatible modality (`ModalityState.any()` or a deliberately captured dialog modality). Couple the callback to a request-local generation/handle, cancel both queued and in-flight work, and check current/disposed state again on the EDT.

## Latest Applicable Path

- Modal-compatible callback helper: [CommitProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L735)
- Two-stage Test and request-local cancellation: [CommitProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L527)
- Fetch/Cancel Profile dialog lifecycle: [ProviderProfileDialog.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L233)
- Queued and in-flight Test regressions: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L1037)
- Acceptance record: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: an IntelliJ modal Settings/`DialogWrapper` launches a cancellable background request whose result must update that UI.
- Steps:
  1. Snapshot immutable request inputs before leaving the EDT.
  2. Create a request-local handle before queueing and make Cancel invalidate it immediately.
  3. Deliver status/results with an explicitly compatible modality.
  4. Recheck generation, handle identity, current Profile/endpoint, and dispose state on delivery.
  5. Cancel the indicator and reject every callback after Cancel, replacement, Apply, Reset, or dispose.
- Verification: accepted r3 152-test suite; focused queued/restart/in-flight Test and Fetch cancellation regressions; no external credential or Provider call. The unchanged route remains covered by the r4 157-test suite.
- Applicability boundary: IntelliJ modal settings/configurable/dialog background operations.
- Fallback: if cross-modality delivery is prohibited, bind callbacks to the exact dialog modality and keep progress/status in that modal UI; do not rely on default non-modal scheduling.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-16 | Commit message helper integration r3 | User clicked Fetch Models and Test inside Settings/Profile dialog | default modality delayed EDT completion until modal UI closed | modal-compatible delivery plus request-local cancellation and stale guards | status/results update while open; cancelled/late work cannot mutate UI |
