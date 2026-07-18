# Commit Message Helper Integration Handoff

Tool: codex
Date: 2026-07-18
Task: integrate the commit message helper into EzCodeMark

Documentation level: `controlled`

## Control Plane Snapshot

- Controller and acceptance owner: `app-root`
- Work-order version: 7
- Canonical documents: [specification](spec.md), [plan](plan.md), [tasks](tasks.md), and [verification](verify.md).
- Last material journal event: `EVT-036`.
- Final state: `complete / accepted`.

## Delivered

- Independent commit-message `domain / data / presentation` vertical slice.
- Four stable VCS Actions, scoped dual-surface toolbar injection, two default shortcuts, Keymap discovery/conflict state, cancellation, and stale-result protection.
- Generate defaults to literal `Ctrl+Alt+X` / `Control+Option+X` across supported Keymaps.
- Structured editor, additional-requirement dialog, direct AI writeback by default, and optional editable AI preview.
- Format accepts an optional non-persistent one-off instruction; cancel stops before Profile/background work, and the service receives only current commit text with no Git context.
- Optional preview supports AI instruction optimization and Commit refinement after exact primary SYSTEM/USER confirmation. It retains first/initial/current values, before/after operations, raw/optimized/confirmed instructions, optimizer notes, and prompt envelopes in dialog memory only; complete history is explicitly viewable/copyable.
- Preview follow-up requests reuse the original Action handle, send no first Commit or Git context, share one three-request budget, and carry the confirmed bounded source request through controlled JSON repair. Cancel, finish, profile change, failure, or late response cannot write or append history.
- Template/type, provider, project-default, draft, PasswordSafe, consent, Git-context, localization, and settings behavior.
- OpenAI-compatible and Anthropic transport with IDE proxy, bounded fallbacks, cancellation, SSE/UTF-8 handling, model listing, and redaction.
- Upstream-aligned LLM Settings and Commit Template layouts/defaults; full-width session-retained masked API-key entry; side-effect-free settings polling; modal-safe Fetch/Test callbacks; mounted fuzzy editable models with coherent query/selection/OK behavior; reliable toolbar and double-click Profile editing; Profile-name synchronization; and stale/cancelled request protection.
- Standard/Concise/custom commit styles, project/global selection, live template preview, reviewed AI style proposal, and a separately registered Select Style Action.
- Global schema 3 with atomic JetBrains common-data and roamable `TOOLS` carriers, UUID ancestry, optimistic cross-process writes, automatic equal/ancestor convergence within the retained 32-ID horizon, and explicit conflict resolution when ancestry evidence is absent or branches diverge. Portable payloads exclude secrets, account material, executable paths, and source consent.
- Project-shared `.idea/ezCodeMarkCommitMessage.xml` ownership for bounded instructions/templates/styles/defaults plus workspace-only project profiles, active selection, namespaced PasswordSafe credentials, drafts, consent, and compatibility overrides.
- ChatGPT / Codex profiles through the official stable App Server protocol with Codex CLI 0.144.5+ discovery, a dedicated cross-product machine account/home, browser/device login, account status/logout, model discovery, concrete output schemas, and no plugin access to token material.
- Fresh ephemeral Codex turns with an allowlisted process environment, isolated XDG/temp/cwd/home roots, a collision-resistant deny-by-default profile, no project instruction sources/history/environment context/MCP/web/network tools, exact effective-isolation validation, buffered final output, and process-tree termination for isolation drift, tool events, malformed/unknown items, timeout, or cancellation before IDs are bound. The official App Server `experimentalApi=true` capability is mandatory only to verify `activePermissionProfile`; unsupported or mismatched responses fail before turn input.
- Isolated version probing, early bounded model deduplication, forward-compatible CAS repair of invalid/missing machine generations, same-revision portable quarantine recovery, and post-Apply Project Defaults resnapshot with project conflict coverage.
- 23-suite / 259-test full suite plus 4 focused suites / 137 tests, plugin ZIP, configuration/structure validation, and both direct exact-cache and canonical Gradle three-target compatibility matrices with no internal-API report. Earlier r1/r2 live commit/settings checks remain historical evidence; no new r3-r7 screenshot-level sandbox claim is made.
- README/user/build/change/technical/current/process documentation, packaged Apache-2.0 notice/license, and seventeen verified error-memory routes.

## Acceptance Evidence

- Exact automated and live results: [verify.md](verify.md).
- Artifact: [EzCodeMarks-1.2.0.zip](../../../../build/distributions/EzCodeMarks-1.2.0.zip).
- Final r7 security/concurrency review and post-verifier review: no P0/P1 remains.
- `doc_drift`: closed for all affected authoritative documents.
- Token/model counters: `usage unavailable`.

## Boundaries Preserved

- No integration with bookmark ToolWindow, ViewModel, SelectionBus, or `.codemark` state.
- No raw Git process, `.form`, internal Darcula/PluginManager API, plaintext API key/OAuth token, runtime Keymap mutation, publish/deploy, production, DB/SQL, real login/logout/inference/source-context provider request, or external write. The earlier credential-free read-only models-route diagnostic remains historical r2 evidence. One isolated credential-free `thread/start` smoke triggered an unauthorized websocket preconnect before any turn input; live checks stopped and the route is archived in project error memory.
- Existing unrelated worktree state was preserved; no publish, push, or external write was performed.

## Residual / Cleanup

- No implementation blocker remains. A new screenshot-level sandbox run was not performed; fixture/event, protocol, cancellation, isolation, synchronization, package, and three-target automated evidence form the r7 acceptance basis.
- Real credentials, ChatGPT login/logout, inference, and source-context transmission were intentionally not exercised; fake App Server, local mock HTTP, strict configuration smoke, and fixture coverage provide the acceptance evidence.
- The earlier Gradle metadata failure is no longer active. The direct CLI 1.409 `.176` matrix and recovered canonical Gradle `.258` matrix both produced three compatible verdicts for the final artifact.
- Remaining limits are explicit and fail-safe: the 32-entry ancestry horizon can require manual conflict resolution, and complete hostile symlink TOCTOU elimination would require a native/descriptor-relative storage redesign. No concrete P0/P1/P2 implementation defect remains.

## Safe Continuation

- The feature can be reviewed from [technical details](../../../knowledge/technical-details.md) and [user guide](../../../../doc/USER_GUIDE.md).
- Generated build and verifier reports remain ignored local evidence. The previously ignored heap dump was deleted after user authorization; no tracked cleanup remains.
