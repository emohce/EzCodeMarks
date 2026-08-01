# Environment Actions / Codex Chat Verification

## Current State

- Decision: automated and local fake-runtime acceptance passed
- Requested model/profile: Root inherited; runtime uses a local fake protocol fixture
- Observed model: `fake-local-model` from the local fixture; no real model inference
- Usage: unavailable
- Baseline: the pre-implementation dirty tree passed 24 suites / 267 tests plus project configuration and structure; its 300-second Plugin Verifier attempt had no verdict.

## Acceptance Evidence

| Unit | Source evidence | Changed files | Local verification | Scope check | Document impact | Root decision |
| --- | --- | --- | --- | --- | --- | --- |
| WU-1 | shared transport / separate caller policy delta | none | source and test seam review | read-only | requirement-canonical | accepted |
| WU-2 | public native Commit, state, Configurable and disposer delta | none | source/API seam review | read-only | requirement-canonical | accepted |
| WU-3 | current implementation and tests | task-owned code/tests/docs | 5 focused suites / 106 tests checkpoint; final 29 suites / 309 tests; config/structure/XML, three IDE targets and fake-runtime host pass | unrelated dirty tree preserved | requirement-canonical + project-current + verification-ledger + project-memory | accepted |

## Automated Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Focused Environment/Chat/strict-provider/settings/descriptor tests | PASS | 5 suites / 106 tests at the focused checkpoint; later edge cases are included in the forced full rerun |
| Full Gradle suite | PASS | final rerun: 29 suites / 309 tests; 0 failure, 0 error, 0 skipped |
| `verifyPluginProjectConfiguration` | PASS | no configuration error |
| `verifyPluginStructure` | PASS | plugin archive/descriptor structure accepted |
| `xmllint --noout plugin.xml` | PASS | descriptor is well formed |
| Three-target Plugin Verifier | PASS | Plugin Verifier 1.409: IU-253.33813.55 Compatible (7 deprecated / 6 experimental), IU-261.26222.65 Compatible (11 / 6), IU-262.9437.65 Compatible (11 / 6); no internal API. Notices point to pre-existing Bookmark/ToolWindow classes, not the new Environment/Chat/Commit implementation. |
| `git diff --check` and document link audit | PASS | no whitespace error; all audited task/current/error-memory code links resolve with line anchors |

## Local Runtime Host Results

| Scenario | Result | Evidence |
| --- | --- | --- |
| Host and identity boundary | PASS | isolated IntelliJ IDEA 2026.1.4 host loaded the plugin; Codex CLI Settings displayed normal Interactive CLI versus isolated Commit Provider identities. No constructor error remained after exposing the Java `(Project)` overload. |
| Configurable transaction/navigation | PASS | an unapplied dummy executable disabled account operations and Cancel restored persisted state; the fake executable persisted only after Apply. ToolWindow buttons then selected Environment Actions and Codex CLI by Configurable class rather than falling back to the prior Settings page. |
| True multi-turn | PASS | one fake App Server process received one `thread/start` and two sequential `turn/start` requests; UI returned `fake reply 1` then `fake reply 2` in the same temporary conversation. |
| Effective policy display | PASS | UI rendered `workspaceWrite`, `approval=on-request`, restricted network, instruction source, one Skill and one plugin from the fake server before the first turn. |
| Approval and Stop | PASS | a fake local-command request rendered an inline redacted summary with Allow/Deny; Deny returned `decline` and completed the turn. A following slow turn exposed Stop, sent `turn/interrupt`, displayed `interrupted` and re-enabled Send. |
| New conversation and lifecycle | PASS | New conversation cleared transcript/permission state, closed the old fake process and produced a new process/thread whose first reply counter reset. Normal IDE exit left no fake CLI process and the fixture logged connection close. |
| Native Commit | PASS | with a non-empty visible Commit ToolWindow message, `PREPARE_COMMIT` displayed Replace / Append / Cancel. Explicit Replace changed only the native editor value to `feat: native prepared`; no file selection, staging, Commit button or raw Git process occurred. |

## Covered Contracts

- schema v1→v2, legacy `GIT_COMMIT`→`PREPARE_COMMIT`, stable slot/order, project selection, corrupt/oversized state, file-lock/CAS competition, disjoint merge and explicit same-field conflict resolution;
- GeneralCommandLine construction, relative Script paths, Windows Codex launcher arguments, stdin one-shot prompts, opt-in non-Git bypass, streamed/bounded output, cancellation/timeout/dispose process-tree termination and approval-needed guidance;
- same-thread multi-turn App Server Chat, inherited policy parameters, normal-home protection, effective permission fail-closed behavior, Skills/plugins status, broad-mode acknowledgement, approvals, interrupt and process death;
- native one-use/expiring Commit message provider, stable Action IDs, no raw Git side-effect source, Configurable Apply/Reset/Cancel, four-bundle parity and descriptor grouping;
- all existing strict `CodexAppServerClient.complete()` isolation, permission, tool-rejection and cancellation regressions.

## Runtime And External Boundary

- No real login, logout, model call, source-context transmission, Git staging/commit, deployment, publication or other external write was performed.
- The runtime host used only an ignored local fake CLI/App Server, an isolated IDE config/system/plugin directory and a disposable small Git project. The test-created common-data Environment was removed from the live JetBrains common-data path after shutdown and retained only as an ignored recoverable fixture.

## Failure And Memory Evidence

- A legacy file without `revision` initially received a new random default on every read, making the first migration CAS impossible. The fixed content-bound migration identity and regression are recorded in [legacy random revision CAS loop](../../../knowledge/error-memory/environment-action-legacy-random-revision-cas-loop.md).
- The first real App Server child-process test showed that closing stdin before enumerating descendants can let the parent exit and reparent its child. Descendants are now captured before EOF; the route is recorded in [process-tree snapshot before stdin close](../../../knowledge/error-memory/process-tree-snapshot-before-stdin-close.md).
- One asynchronous unsupported-request assertion initially observed the JSON-RPC error before the immediately following UI event. The production ordering was valid; the test now waits for both independently. This local test synchronization did not warrant a third durable memory record.
- Real host execution proved four additional runtime-only failure patterns: [Java-visible project Configurable constructors](../../../knowledge/error-memory/intellij-project-configurable-java-constructor.md), [inline stoppable ToolWindow approvals](../../../knowledge/error-memory/intellij-toolwindow-inline-approval.md), [typed Configurable Settings navigation](../../../knowledge/error-memory/intellij-show-settings-configurable-class.md), and [focused/non-modal native Commit editor discovery](../../../knowledge/error-memory/intellij-commit-editor-discovery.md).

## Computer Use Route Memory Supplement

- The accepted runtime evidence is normalized in the [verified IntelliJ smoke route](../../../knowledge/computer-use/jetbrains-plugin-smoke.md#L1): genuine IntelliJ app shell, isolated config/system/log, actual Gradle plugin sandbox, disposable Git project and local fake Codex runtime.
- Read-only revalidation confirmed the installed app bundle, isolated `idea.properties`, fake CLI fixture, disposable Git project and prepared plugin sandbox still exist; no IDE, model, account, Commit or Computer Use session was started for this documentation-only supplement.
- Project failures are split by fingerprint into [bare `runIde` host attachment](../../../knowledge/error-memory/intellij-plugin-smoke-bare-runide-host.md#L1) and [Swing popup accessibility bridge loss](../../../knowledge/error-memory/intellij-swing-popup-accessibility-bridge-loss.md#L1). Cross-project equivalent-retry and key-chord-shape failures remain in the linked CodeNote global archive rather than being copied here.
- Future smoke runs reuse the route and rerun only when its app/platform, Gradle sandbox, Computer Use capability, fake protocol, scenario or cleanup dependency changes.

## Project Per-Call Session Ledger Correction

- Project evidence now follows the global [`computer-use-session/v1` contract](../../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/memory/computer-use-routes/session-record-v1.md#L1) and is indexed in [EzCodeMark sessions](../../../knowledge/computer-use/sessions/README.md#L1).
- Every future Computer Use method is logged before the next method with sanitized input, pre/post state, result, assertion, operational decision, impact, confirmation/fallback and cleanup. The detailed ledger remains project-local even when route/error memory is unchanged.
- The prior accepted smoke has a [reconstructed partial record](../../../knowledge/computer-use/sessions/2026-08-01-intellij-environment-actions-smoke.md#L1). It preserves known material events and explicit gaps but does not fabricate missing call counts, timestamps, indices, coordinates or method boundaries.
- Global extraction contains only generalized route/retry/key-shape/session-layering rules and error fingerprints with backlinks. The project event table is not copied into CodeNote.
- Docs/rules closeout passes the EzCodeMark working project audit, code-link audit, `git diff --check` and bounded documentation receipt. CodeNote separately passes 213 rule tests, 9 security replay cases and deterministic `computer-use` route resolution. No IDE, Computer Use or Gradle application test was rerun for this correction.

## Required Gates

- Focused data, process, App Server, VCS, settings, and ToolWindow tests.
- Full `test`, `verifyPluginProjectConfiguration`, and `verifyPluginStructure`.
- `git diff --check` and plugin XML parsing.
- Configured three-target Plugin Verifier matrix.
- Document code-link audit and current-authority synchronization.
- Runtime/visual checks explicitly recorded as passed, failed, or unavailable.

## Residual Decision

- No implementation, automated compatibility or local runtime blocker remains.
- Local fake-runtime acceptance is `pass`; real credentials, model calls, source transmission and Git side effects remain intentionally excluded and are not residual gates.
- Documentation impact is closed at requirement-canonical, project-current, verification-ledger and project-memory layers. No DB memory, global rule/template or developer-soul propagation is required.

## Final Review

- Requirement alignment: PASS. Commit Provider isolation remains unchanged; Chat is a real thread/turn session; Environment/project state boundaries, process lifecycle, native Commit ownership and native Configurable interactions match the accepted specification.
- Plan-to-implementation coverage: PASS. Every implementation and verification work unit is closed; runtime-only defects found during host acceptance were fixed before acceptance.
- Risk and compatibility: PASS. No raw Git side effect or external credential/model operation was introduced; all three configured IDE targets remain Compatible with no internal API.
- Findings: P0 none; P1 none; P2 none.
- Not checked by design: real account mutation, inference/source transmission, final Git staging/Commit, publish/deploy, DB/SQL and external-service writes.
