---
id: ezcodemark-codex-app-server-permission-profile-isolation
status: verified
scope: project
fingerprint: codex-app-server-fixed-or-legacy-permission-config-can-merge-with-persisted-home-state-and-weaken-turn-isolation
first_seen: 2026-07-17
last_verified: 2026-07-18
review_after: 2027-01-17
evidence:
  - Codex CLI 0.144.5 strict-config isolation smoke
  - fake-process thread configuration and isolation regressions
  - final r7 review and verification
tags:
  - codex-app-server
  - permissions
  - sandbox
  - security
---

# Codex App Server Permission Profile Isolation

## Symptom

A Codex App Server thread can report an unexpected active permission profile or effective sandbox when a request relies on a legacy sandbox override, a built-in profile name, or a fixed custom profile that can merge with persisted state in the dedicated Codex home.

## Wrong Assumption

Treating `read-only`, `approvalPolicy = never`, or `--strict-config` as proof that the effective thread cannot inherit a broader filesystem or network permission profile.

## Verified Root Cause

Codex resolves named permission profiles from layered configuration. A stable profile name can collide with persisted configuration, while turn-level sandbox fields do not prove which profile became active. Isolation must be expressed as a complete deny-by-default profile and verified from the effective `thread/start` response.

The current implementation creates a fresh profile ID for each client, denies `:root`, permits only `:minimal` plus the empty isolated working directory, disables network access, and rejects the thread before `turn/start` unless the exact profile ID, working directory, empty instruction sources, `never` approval policy, read-only sandbox, and ephemeral state are confirmed. Client initialization explicitly negotiates the official App Server `experimentalApi=true` capability because `activePermissionProfile` is mandatory evidence; unsupported or missing evidence fails closed. See [CodexAppServerClient.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerClient.kt#L241) and its [regression suite](../../../src/test/kotlin/emohce/data/commitmessage/CodexAppServerClientTest.kt#L55).

## Detection Order

1. Require successful `experimentalApi=true` capability negotiation; do not assume the effective profile is available otherwise.
2. Inspect the complete permission profile sent in `thread/start` rather than a turn-level sandbox label.
3. Require a collision-resistant profile ID and deny-by-default filesystem/network entries.
4. Inspect `activePermissionProfile`, `sandbox`, `cwd`, `instructionSources`, and the returned thread's ephemeral state.
5. Stop the process before `turn/start` on any mismatch.
6. Fail the process on every unknown or tool-capable turn item, notification, or server request.

## Prevention Rule

Never infer Codex isolation from a profile name or approval policy alone. Define a complete fresh profile and validate the effective response before supplying user input.

## Latest Applicable Path

- Runtime boundary: [CodexAppServerClient.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerClient.kt#L241)
- Acceptance evidence: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: a supported Codex App Server version is used for text-only structured completion.
- Steps:
  1. Start Codex with an isolated home and working directory plus strict configuration.
  2. Create a fresh deny-by-default permission profile with network disabled.
  3. Disable project instructions, environment context, history persistence, MCP, shell, browser, delegation, and related tool features.
  4. Negotiate `experimentalApi=true` and validate the exact effective profile and isolation fields returned by `thread/start`.
  5. Start the turn only after validation; terminate on mismatch or any non-text/reasoning operation.
- Verification: Codex CLI 0.144.5 accepted the profile shape, and the 30-test fake-process suite proves capability negotiation, exact-profile rejection, no turn after mismatch, bounded model discovery, isolated process setup, and fail-closed protocol/tool/event handling.
- Applicability boundary: the stable 0.144.5 App Server contract used by EzCodeMark; newer protocol behavior must pass the same effective-response checks.
- Fallback: reject the ChatGPT/Codex provider as unavailable rather than relaxing isolation.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17 | Commit message helper r7 | Security review of ephemeral Codex turns | Legacy/fixed permission assumptions without exact active-profile proof | Fresh deny-by-default profile plus effective-response validation | Strict smoke and regressions passed |
