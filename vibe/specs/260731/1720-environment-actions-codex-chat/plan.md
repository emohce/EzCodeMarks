# Environment Actions / Codex Chat Execution Plan

## Implementation Sequence

1. Freeze task authority and obtain bounded read-only evidence for App Server and IntelliJ integration seams.
2. Implement schema v2, project activation, native Commit preparation, and cancellable execution without changing stable Action IDs.
3. Add the interactive App Server session and lifecycle-owned ToolWindow UI while preserving strict Commit Provider policy.
4. Normalize Configurable ownership, localization, descriptor grouping, and documentation.
5. Run focused/full verification, inspect the final diff, synchronize authorities, and decide acceptance.

## Execution Topology

| Work Unit | Owner | Surface | Profile | Dependencies | Allowed scope | Excluded scope | Output | Verification owner | Fallback |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WU-1 App Server delta | Explorer | native-thread | `explorer_terra` | task authority | Read current Codex client/protocol/tests | writes, policy decisions | minimal extraction/session seams and risks | Root | Root source inspection |
| WU-2 IntelliJ integration delta | Explorer | native-thread | `explorer_terra` | task authority | Read VCS/settings/state/ToolWindow APIs and current code | writes, canonical docs | concrete native Commit/state/lifecycle seams | Root | Root source inspection |
| WU-3 implementation and closeout | App Root | main | Root | WU-1/WU-2 evidence | all task-owned code/tests/docs | unrelated dirty changes, external writes | verified implementation and synchronized authorities | App Root | reduce optional polish, keep safety gates |

## Authority Packets

### WU-1

- Authority: this spec; Commit Message r7 spec; current `CodexAppServerClient`, service, tests; current Environment Actions panel/executor.
- Known constraints: commit isolation cannot relax; interactive chat inherits normal CLI config and is ephemeral.
- Question: smallest safe transport/session split and exact protocol/state/cancellation coverage.
- Required evidence: symbols, call chain, reusable test fixtures, concrete regressions to lock.
- Provisional document impact: requirement-canonical.

### WU-2

- Authority: this spec; plugin descriptor; current Environment settings/actions/panel; Commit action adapter and platform tests.
- Known constraints: native Commit only, stable slot IDs, per-project active Environment, non-roaming common store, no bookmark coupling.
- Question: exact public IntelliJ seams and migration/persistence/UI risks.
- Required evidence: symbols/action IDs/extension points/tests and compatibility gaps.
- Provisional document impact: requirement-canonical.

## Verification Decision

- Route: focused unit/integration tests first, then full Gradle test/configuration/structure, XML/diff checks, and the configured verifier matrix.
- Owner: App Root.
- Runtime UI: separate evidence; compilation/tests do not imply visual acceptance.
- Residual risk: user-installed Codex versions and native Commit modal/non-modal differences require capability/error-path coverage.

## Completion Result

- WU-1 and WU-2 read-only evidence was reconciled; WU-3 delivered the task-owned implementation, tests and authority synchronization without taking ownership of unrelated dirty changes.
- Automated acceptance is complete: 29 suites / 309 tests, project configuration, plugin structure, XML and IU-253/IU-261/IU-262 compatibility all pass.
- The local fake-runtime lane also passed in IntelliJ 2026.1.4 after unlock. Runtime findings corrected the project Configurable constructor, blocking approval surface, settings-page selection and visible Commit-editor lookup; each correction has a regression and durable prevention record.
- No acceptance lane remains open. Real credentials, inference, source transmission, Git staging/Commit and external writes remain intentionally outside scope rather than substituted for fake-runtime evidence.
