---
id: ezcodemark-process-tree-snapshot-before-stdin-close
status: verified
scope: project
fingerprint: closing-process-stdin-before-enumerating-descendants-allows-parent-to-exit-and-reparent-children-before-tree-termination
first_seen: 2026-07-31
last_verified: 2026-07-31
review_after: 2027-01-31
evidence:
  - App Server EOF-parent descendant termination regression
  - focused transport regression
  - 306-test current-tree suite
tags:
  - process-lifecycle
  - app-server
  - cancellation
  - intellij-platform
---

# Snapshot Process Descendants Before Closing stdin

## Symptom

Closing an App Server connection terminates the parent process, but a child process can remain alive even though shutdown code subsequently asks the parent for its descendants.

## Wrong Assumption

Assuming the parent process remains alive and retains its process-tree relationship after its stdin is closed.

## Verified Root Cause

Some CLI parents exit immediately on stdin EOF. If descendant enumeration happens after closing the writer, the operating system may already have reparented the child, so `ProcessHandle.descendants()` returns an empty set and later tree cleanup cannot find it.

The transport now snapshots descendant handles before closing stdin and carries that snapshot through graceful, destroy and force-destroy phases. See [CodexAppServerConnection.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerConnection.kt#L351) and [CodexAppServerConnectionTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/CodexAppServerConnectionTest.kt#L17).

## Detection Order

1. Start a parent that spawns a long-lived child and waits for stdin EOF.
2. Record the child PID without writing protocol output.
3. Close the connection and check both the parent and recorded child.
4. If only the child survives, inspect whether descendant enumeration occurred after writer/EOF closure.

## Prevention Rule

When shutdown can make the parent exit, capture descendant handles before the triggering close/destroy operation. Apply graceful, destroy and force-destroy phases to the captured handles as well as the root process.

## Latest Applicable Path

- Runtime: [CodexAppServerConnection.kt](../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerConnection.kt#L351)
- Regression: [CodexAppServerConnectionTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/CodexAppServerConnectionTest.kt#L17)
- Acceptance: [verify.md](../../specs/260731/1720-environment-actions-codex-chat/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: the client owns the root process and can enumerate descendants before initiating shutdown.
- Steps:
  1. Snapshot descendants before closing stdin or destroying the root.
  2. Close stdin and allow a bounded graceful exit.
  3. Destroy all still-live captured descendants and the root.
  4. After a bounded wait, force-destroy every remaining captured handle.
- Verification: a real shell parent exited on stdin EOF while its long-lived child was still identified and terminated; the full suite passed.
- Applicability boundary: descendants present at shutdown snapshot time. A child intentionally detached before the snapshot requires process-group/job-object ownership.
- Fallback: launch the CLI inside an OS process group or job object when hostile or self-daemonizing descendants are in scope.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-31 | Environment Actions / Codex Chat | New EOF-parent process-tree regression | Enumerated descendants after writer close | Snapshot before EOF and terminate captured handles through all phases | Fixed; focused and full gates passed |
