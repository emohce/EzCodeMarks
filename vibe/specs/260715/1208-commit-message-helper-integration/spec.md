# Commit Message Helper Integration Specification

Tool: codex
Date: 2026-07-15
Task: integrate the commit message helper into EzCodeMark

Documentation level: `controlled`

## Task Documentation Sync Group

- Group key: `dsg:ezcodemark:commit-message-helper-v1`
- Group owner: this `spec.md`
- Task root: `vibe/specs/260715/1208-commit-message-helper-integration/`
- Durable members: [raw requirement](raw-requirement.md), this specification, [plan](plan.md), [tasks](tasks.md), [verification](verify.md), [handoff](handoff.md), [project status](../../PROJECT_STATUS.md), [README](../../../../README.md), [user guide](../../../../doc/USER_GUIDE.md), and [technical details](../../../knowledge/technical-details.md).
- Code/config dependencies: [build configuration](../../../../build.gradle.kts#L23), [plugin descriptor](../../../../src/main/resources/META-INF/plugin.xml#L22), and [commit-message Actions](../../../../src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L42).
- Memory authority: [error-memory index](../../../knowledge/error-memory/README.md).
- App Root owns reconciliation and acceptance; no external archive write is required.

## Requirement

- Implement an independent `domain / data / presentation` commit-message feature with structured drafting, rendering, AI providers, privacy-preserving Git context, native settings/dialogs, four stable Actions, and shortcut injection.
- Preserve existing EzCodeMark bookmark behavior and settings.
- Treat the approved user plan and [raw requirement](raw-requirement.md) as canonical.

## Accepted Behavior

- Four Actions are registered once with stable IDs and required order, native icons, cancellation semantics, and default Create/Generate shortcuts.
- Create, Generate, Generate With Context, and Format use immutable EDT snapshots plus cancellable background work; document stamp/text checks prevent stale writeback.
- The feature contains templates/types, application and workspace state, multiple provider profiles, PasswordSafe credentials, source-context consent, bounded Git context, editable previews, and English/Chinese/Japanese/Korean bundles.
- OpenAI-compatible and Anthropic transports support IDE proxy, timeouts, cancellation, streaming parsing, reasoning/streaming compatibility fallback, response repair, error redaction, model listing, and a three-request budget.
- Public IntelliJ/VCS/Git4Idea/Velocity APIs are used. No raw Git process, `.form`, internal Darcula class, new feature ToolWindow, plaintext secret persistence, or bookmark-state coupling was added.
- Tests, packaging, configuration/structure checks, three-target Plugin Verifier, source review, documentation audit, and live UI checks pass as recorded in [verify.md](verify.md).

## Actual Architecture Adaptation

- The traditional commit dialog reports Action place `CommitMessage`; IDEA 2025.3's non-modal Commit ToolWindow reports `ChangesView.CommitToolbar`.
- Toolbar visibility therefore recognizes both commit toolbar places and additionally requires `event.isFromActionToolbar`. Keymap, Find Action, and direct shortcut calls remain unaffected.
- This is a runtime-surface conversion of the approved `CommitMessage`-only assumption, not a feature-scope change.

## Constraints

- Kotlin 2.4.0, JDK 21, IntelliJ IDEA 2025.3, and the current Gradle IntelliJ plugin remain authoritative.
- API keys exist only in PasswordSafe. Source context is filtered, capped, and consent-gated with no bypass in v1.
- AI output is previewed before writeback and never streamed directly into the commit editor.
- Existing user/worktree changes are preserved. No publish/deploy, DB/SQL, credential disclosure, or external provider call is authorized by this task.

## Prior Task Overlap

- Relationship: `none`; no reusable prior task was present in the project hub.
- Document governance: new Controlled task; repository `vibe/` remains authoritative.
- Delta: the commit-message slice and task ledger are net-new; relevant build, plugin, user, technical, and current-state docs were updated.

## Requirement Versioning

```yaml
spec_id: ECM-COMMIT-MESSAGE-001
spec_revision: 1
status: implemented-and-verified
raw_sources: [RAW-001]
targets:
  - canonical_manifest: task-local controlled specification
    base_full_version: none
    result_full_version: ECM-COMMIT-MESSAGE-001-r1
delta:
  - requirement_id: CM-001
    operation: add
    before: no commit-message assistant in EzCodeMark
    after: native commit-message assistant with UI, Actions, shortcuts, providers, privacy, and tests
    raw_refs: [RAW-001]
    confirmation: explicit
    canonical_location: this specification
memory_used: []
memory_updates:
  - intellij-patched-coroutines-runtime-shadowing
  - intellij-ant-instrumenter-task-race
  - httpurlconnection-response-code-timeout-retry-oom
  - plugin-verifier-multi-ide-first-run-timeout
  - intellij-commit-toolbar-action-place-visibility
open_questions: []
```

- Merge status: complete; no missing implementation target.
- Actual surface: `actual`.
- Requirement-runtime gap: closed.

## Execution Authority And Boundaries

- Control plane and sole decision owner: App Root Thread.
- Read-only agents supplied bounded repository/API reviews; App Root owned every write, risk decision, current-tree verification, documentation synchronization, and acceptance.
- High-risk boundaries remained closed: no production, credential disclosure, publish, deletion, DB/SQL, or external service writes.
- The generated 2.6GB heap dump is ignored but retained pending explicit deletion authorization.

## Evidence

- Baseline repository and upstream implementation were inspected before implementation.
- Current source received independent static architecture/privacy reviews; all P0/P1 findings were resolved and Root-reverified.
- Full automated and live UI evidence is recorded in [verify.md](verify.md); final state is accepted.

## TaskExperienceObservation

- Result: complete and accepted; 119 tests and all packaging/compatibility gates pass.
- Unavailable dimension: model/token counters remain `usage unavailable`.
- Persistence: five verified reusable failure routes were promoted to [project error memory](../../../knowledge/error-memory/README.md); no global rule/template propagation was needed.
