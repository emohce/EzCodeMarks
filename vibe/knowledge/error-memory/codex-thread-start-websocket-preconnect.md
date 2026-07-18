---
id: ezcodemark-codex-thread-start-websocket-preconnect
status: verified
scope: project
fingerprint: codex-app-server-thread-start-can-open-account-websocket-preconnect-before-turn-input
first_seen: 2026-07-17
last_verified: 2026-07-18
review_after: 2027-01-17
evidence:
  - credential-free isolated Codex CLI 0.144.5 thread-start smoke returned websocket 401
  - fake-process protocol and cancellation regressions
  - final r7 verification record
tags:
  - codex-app-server
  - network
  - verification
  - privacy
---

# Codex Thread Start Websocket Preconnect

## Symptom

A credential-free `thread/start` compatibility smoke attempted an account websocket preconnect and received HTTP 401 even though no `turn/start`, inference input, login, logout, or source context was sent.

## Wrong Assumption

Treating `thread/start` as a purely local configuration check because model tools and sandbox network access are disabled.

## Verified Root Cause

The App Server's own account/model transport is separate from model tool permissions. Codex CLI 0.144.5 can initialize an account websocket during thread creation before any turn input. A read-only sandbox therefore does not make a live thread-start smoke network-free.

## Detection Order

1. Classify live `thread/start` as potentially external before using it for verification.
2. Check whether the task authorizes external account/model transport, not merely tool-free inference.
3. Prefer schema/source inspection and the fake-process suite for request/response compatibility.
4. If a live compatibility check is separately authorized, use an empty isolated home, send no turn input, retain no response data, and stop after the minimum response.
5. Record any unexpected transport and do not retry it silently.

## Prevention Rule

Under a no-external-account verification boundary, do not execute live `thread/start`. Sandbox network denial governs model operations, not App Server account preconnect behavior.

## Latest Applicable Path

- Fake protocol suite: [CodexAppServerClientTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/CodexAppServerClientTest.kt#L51)
- Acceptance boundary: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: protocol compatibility must be checked without account or inference traffic.
- Steps:
  1. Validate method and field shapes against the installed stable schema/source contract.
  2. Exercise initialize, account/model responses, thread/turn state, cancellation, isolation rejection, and tool rejection through the deterministic fake process.
  3. Build the plugin and run the focused/full suites.
  4. Do not add a live thread smoke unless the user explicitly authorizes its external transport boundary.
- Verification: the final fake-process suite passed 30 Codex client tests, including capability negotiation, unresolved start cancellation, exact isolation, bounded model pagination, malformed items, process bounds, and tool-event termination.
- Applicability boundary: automated and local acceptance where real login, inference, logout, source transmission, and external account writes are excluded.
- Fallback: leave runtime compatibility unclaimed and require a separately authorized manual account acceptance check.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17 | Commit message helper r7 | Credential-free isolation smoke | Assuming thread creation was network-free | Stopped live checks and completed fake-process/source verification | No turn input or account mutation; durable prevention recorded |
