---
id: ezcodemark-intellij-configurable-ismodified-secret-side-effects
status: verified
scope: project
fingerprint: intellij-configurable-ismodified-clears-passwordfield-fetch-missing-api-key
first_seen: 2026-07-16
last_verified: 2026-07-16
review_after: 2027-01-16
evidence:
  - user-confirmed API key field clearing and Fetch Models missing-key symptom
  - CommitMessagePlatformIntegrationTest credential-session regressions
  - IntelliJ/Aqua settings fixture lifecycle coverage
tags:
  - intellij-platform
  - settings
  - password-safe
  - credentials
  - ui
---

# IntelliJ Configurable `isModified()` Secret Side Effects

## Symptom

Typing an API key in an IntelliJ settings page appears to work, but the masked value disappears automatically. Fetch Models then reports that the key is missing. The same row can also collapse the password field when a long status comment shares its layout cell.

## Wrong Assumption

Treating `Configurable.isModified()` as a convenient place to capture mutable form state, including reading and clearing a `JBPasswordField`.

## Verified Root Cause

IntelliJ polls `isModified()` repeatedly while a settings page is open. The former implementation called a profile-save routine from that query path; the routine copied the password and cleared the field. That made an IDE lifecycle query mutate the visible credential and left later model requests without the current value.

The corrected [settings query](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L206) is pure. The Profile dialog captures the secret only at explicit result/request boundaries, retains temporary material in wipeable arrays, and renders a [full-width password row](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L88). Request resolution [prefers the entered/session key and blocks pending-clear fallback](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L669), while Apply performs credential/state updates transactionally.

## Detection Order

1. Type a non-sensitive test value and observe whether it survives several `isModified()` calls and focus changes.
2. Inspect `isModified()` and other framework query callbacks for writes, field clearing, secret reads, or profile capture.
3. Verify pre-Apply Test connection / Fetch Models receives the session value.
4. Verify Apply success and Apply failure both preserve the masked session value.
5. Mark a credential for clearing and verify requests neither read PasswordSafe nor reach the Provider.
6. Change the endpoint or cancel a request and verify a late result cannot update the model selector.

## Prevention Rule

Keep IntelliJ `Configurable` query methods side-effect free. Capture secrets only on explicit dialog acceptance, Apply, or immutable request snapshot; store session material per stable Profile ID in wipeable arrays. Make deletion an explicit confirmed state applied transactionally, and never let a pending deletion fall back to the stored credential.

## Latest Applicable Path

- Settings query and transactional Apply: [CommitProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L206)
- Full-width Profile credential entry: [ProviderProfileDialog.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/ProviderProfileDialog.kt#L88)
- Credential-session and session-key request regressions: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L647)
- Failed-Apply retention coverage: [CommitMessagePlatformIntegrationTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L915)
- Acceptance record: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: an IntelliJ `Configurable` edits PasswordSafe-backed credentials and can invoke Provider diagnostics before Apply.
- Steps:
  1. Make `isModified()` compare form snapshots without mutating UI or secret state.
  2. Retain the current masked key in a per-Profile session buffer and wipe temporary copies after use.
  3. Snapshot Profile and key before entering a cancellable background request.
  4. Treat confirmed clear as a pending deletion that blocks stored-key fallback until Apply or Reset.
  5. Accept model results only when request generation, Profile, Provider, and endpoint still match.
- Verification: accepted r3 20-suite / 152-test suite plus focused credential/model lifecycle regressions and IntelliJ/Aqua component lifecycle coverage; no real credential was used. The unchanged route remains covered by the r4 157-test suite.
- Applicability boundary: IntelliJ settings pages that edit credentials or other one-shot sensitive values.
- Fallback: if masked session retention is disallowed by a future policy, show only an explicit stored/not-stored state and require re-entry for diagnostics; do not mutate the field from `isModified()`.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-16 | Commit message helper integration r2 | User entered an OpenAI-compatible key and invoked Fetch Models | `isModified()` captured and cleared the password field | Pure query, per-Profile masked session, explicit clear, immutable request snapshot | Fetch Models uses the current key and the field remains masked |
