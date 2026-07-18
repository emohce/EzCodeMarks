# Commit Message Helper Integration Specification

Tool: codex
Date: 2026-07-18
Task: integrate the commit message helper into EzCodeMark

Documentation level: `controlled`

## Task Documentation Sync Group

- Group key: `dsg:ezcodemark:commit-message-helper-v1`
- Group owner: this `spec.md`
- Task root: `vibe/specs/260715/1208-commit-message-helper-integration/`
- Durable members: [raw requirement](raw-requirement.md), this specification, [plan](plan.md), [tasks](tasks.md), [verification](verify.md), [handoff](handoff.md), [project status](../../PROJECT_STATUS.md), [README](../../../../README.md), [English README](../../../../README_EN.md), [user guide](../../../../doc/USER_GUIDE.md), [build guide](../../../../doc/build-guide.md), [change log](../../../../doc/change-log.md), [technical details](../../../knowledge/technical-details.md), [project rule entry](../../../rules/README.md), [documentation rules](../../../rules/documentation.md), [third-party notices](../../../../THIRD_PARTY_NOTICES.md), and the task-linked records in the [error-memory index](../../../knowledge/error-memory/README.md).
- Code/config dependencies: [build configuration](../../../../build.gradle.kts#L1), [plugin descriptor](../../../../src/main/resources/META-INF/plugin.xml#L1), [commit-message Actions](../../../../src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L53), [global portable settings](../../../../src/main/kotlin/emohce/data/commitmessage/CommitMessageSettingsService.kt#L159), [portable store](../../../../src/main/kotlin/emohce/data/commitmessage/CommitMessagePortableStore.kt#L222), [project shared state](../../../../src/main/kotlin/emohce/data/commitmessage/CommitProjectSharedSettingsService.kt#L54), [project private state](../../../../src/main/kotlin/emohce/data/commitmessage/CommitProjectStateService.kt#L41), [Codex service](../../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerService.kt#L67), [Codex client](../../../../src/main/kotlin/emohce/data/commitmessage/CodexAppServerClient.kt#L241), [Codex provider](../../../../src/main/kotlin/emohce/data/commitmessage/CodexLlmProviderClient.kt#L19), [global LLM settings](../../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProvidersConfigurable.kt#L76), [project provider settings](../../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectProvidersConfigurable.kt#L53), [project shared settings](../../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectSharedConfigurable.kt#L36), [Codex regressions](../../../../src/test/kotlin/emohce/data/commitmessage/CodexAppServerClientTest.kt#L51), [portable-state regressions](../../../../src/test/kotlin/emohce/data/commitmessage/CommitMessagePortableStoreTest.kt#L22), and [platform regressions](../../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessagePlatformIntegrationTest.kt#L142).
- Memory authority: [error-memory index](../../../knowledge/error-memory/README.md), [Codex permission isolation](../../../knowledge/error-memory/codex-app-server-permission-profile-isolation.md), [Codex thread preconnect](../../../knowledge/error-memory/codex-thread-start-websocket-preconnect.md), [bounded model pagination](../../../knowledge/error-memory/codex-model-list-retention-before-dedup.md), [machine generation repair](../../../knowledge/error-memory/codex-machine-auth-generation-repair.md), [portable quarantine recovery](../../../knowledge/error-memory/portable-quarantine-revision-only-recovery.md), [Configurable Apply resnapshot](../../../knowledge/error-memory/intellij-configurable-apply-stale-baseline.md), and [Plugin Verifier offline metadata fallback](../../../knowledge/error-memory/plugin-verifier-gradle-offline-metadata-fallback.md).
- App Root owns reconciliation and acceptance; no external archive write is required.

## Requirement

- Implement an independent `domain / data / presentation` commit-message feature with structured drafting, rendering, AI providers, privacy-preserving Git context, native settings/dialogs, four stable Actions, and shortcut injection.
- Preserve existing EzCodeMark bookmark behavior and settings.
- Treat the approved user plan and [raw requirement](raw-requirement.md) as canonical.

## Active Revision 7 Requirement

- Add a distinct ChatGPT/Codex provider backed by the official `codex app-server` stable stdio protocol. A supported user-installed Codex executable is required; EzCodeMark does not copy direct OAuth or ChatGPT backend transport code.
- Use one EzCodeMark-owned machine account under a dedicated cross-product Codex home. Codex owns browser/device login, token persistence/refresh, account status, model discovery, and logout; EzCodeMark never reads token material.
- Portable global state has two synchronized carriers: an atomic common-data snapshot for same-machine cross-product sharing without Backup and Sync, and a roamable IntelliJ `TOOLS` state for Backup and Sync. Equal revisions and ancestor revisions within the retained 32-ID horizon converge; missing ancestry evidence or divergent revisions require explicit conflict resolution.
- Project-shared state owns project extra instructions, templates, styles, and shared defaults in a VCS-eligible project file. Workspace state owns project-private profiles, active profile selection, drafts, credentials, consent, and compatibility overrides.
- Persistent extra instructions are additive configuration, not a complete system-prompt replacement. Project mode is `INHERIT`, `APPEND`, or `REPLACE`; built-in schema, factuality, privacy, and safety instructions always remain authoritative.
- Effective profile resolution is project-private selection, then global active profile, then the existing safe missing-profile error. Existing workspace template/style choices retain compatibility precedence until explicitly moved to shared project defaults.
- Every Codex inference uses a fresh ephemeral thread, isolated Codex home/cwd, no project instruction sources, no project/home readable roots, `never` approvals, structured output, buffered final application, and interruption on cancellation or any tool event.
- Client initialization explicitly negotiates the official App Server `experimentalApi=true` capability because the returned `activePermissionProfile` is required to prove effective isolation. Missing capability support, a missing profile, or any profile mismatch rejects the provider before turn input; there is no relaxed fallback.
- Existing OpenAI-compatible and Anthropic profiles, API-key PasswordSafe behavior, request budgets, privacy filtering, optional preview/refinement, and Actions remain compatible.
- Codex model discovery deduplicates before retention and enforces raw-entry, cursor, page, and retained-text bounds. Invalid or missing machine auth generations are repaired through forward-compatible CAS and remain stable across processes/restarts. Portable quarantine revalidates same-revision replacement content, and successful partial Project Defaults Apply resnapshots preserved external values.

## Accepted Behavior

- Four Actions are registered once with stable IDs and required order, native icons, cancellation semantics, and default Create/Generate shortcuts.
- Generate uses the literal cross-system `C-A-X` default: `Ctrl+Alt+X` on Windows/Linux and `Control+Option+X` on macOS; users may rebind it in the IDE Keymap.
- Create, Generate, Generate With Context, and Format use immutable EDT snapshots plus cancellable background work; document stamp/text checks prevent stale writeback.
- The feature contains templates/types/styles, application and workspace state, multiple provider profiles, PasswordSafe credentials, source-context consent, bounded Git context, optional editable previews, and English/Chinese/Japanese/Korean bundles.
- OpenAI-compatible and Anthropic transports support IDE proxy, timeouts, cancellation, streaming parsing, reasoning/streaming compatibility fallback, response repair, error redaction, model listing, and a three-request budget.
- LLM Settings restores the upstream Active Model/global-options header, Profile table, and Profile dialog defaults/layout. The full-width PasswordSafe-backed key remains masked for the settings session, survives Apply and PasswordSafe write failure, and is available to Test / Fetch models before Apply. `isModified()` is side-effect free. Toolbar Edit and a valid left-button row double-click resolve the selected view row, preserve selection through state synchronization, and open the same Profile editor.
- The editable model selector uses an editor mounted through the public ComboBox setters, synchronizes typed text immediately, performs debounced exact/prefix/substring/subsequence filtering, and preserves selected or custom IDs through popup selection and `OK`. Fetch Models re-filters text entered while a request is in flight. Fetch Models and two-stage Test expose immediate status, use request-local cancellation handles, run modal-compatible EDT callbacks, and reject results after cancellation, dispose, Profile/provider/endpoint changes, or request replacement.
- Commit Template restores the upstream Template/Type layout and adds a Style tab. Standard preserves original behavior; Concise and custom styles can alter the effective prompt and optional template. AI style proposals send no Git/source context, use immutable/stale-guarded requests, and require reviewed application.
- Generate and Format write validated results directly by default. Preview is opt-in and never streams partial output into the commit editor. Select Style is a separately registered project Action available from Find Action, Keymap, and Tools while the fixed toolbar group remains exactly four Actions.
- Format first accepts an optional, non-persistent one-off optimization instruction. Cancel returns before Profile access/background work; the AI service receives only the current commit text plus the instruction/template/style and cannot access Git changes or revision through its method contract. Controlled JSON repair retains the one-off instruction.
- When review-before-write is enabled, the AI result page supports iterative refinement. It keeps immutable first-commit and initial-AI-result values plus the current final result and successful operations in dialog memory only. Each operation records before/after Commit text, raw/AI-optimized/user-confirmed instructions, optimizer notes, and both confirmed prompt envelopes; complete history can be viewed or explicitly copied.
- Prompt optimization and Commit refinement display the exact expanded SYSTEM/USER prompt envelope and require confirmation before sending. The envelope never contains credentials/headers, Git context, or the first Commit; changing an instruction invalidates its prior confirmation.
- One explicit refinement attempt shares a three-request budget: optional prompt optimization, structured Commit refinement, and at most one JSON repair. Repair carries forward the user-confirmed bounded source request before adding invalid output. Cancel, failure, stale input, or dialog disposal never changes the result or appends history.
- Credential deletion is an explicit safe implementation decision: `Clear API key…` requires confirmation and becomes durable only on Apply; a blank field alone never deletes the stored key and a pending clear never falls back to it.
- Public IntelliJ/VCS/Git4Idea/Velocity APIs are used. Velocity file loading, unsafe directives/references/members, ranges, and oversized source/output are execution-side rejected. No raw Git process, `.form`, internal Darcula class, new feature ToolWindow, plaintext secret persistence, or bookmark-state coupling was added.
- Tests, packaging, configuration/structure checks, the exact three-target Plugin Verifier matrix, source review, documentation audit, and automated IntelliJ/Aqua UI fixture checks pass as recorded in [verify.md](verify.md). Earlier r1/r2 live commit/settings checks remain historical evidence; r3-r7 do not add a new screenshot-level sandbox acceptance claim.

## Actual Architecture Adaptation

- The traditional commit dialog reports Action place `CommitMessage`; IDEA 2025.3's non-modal Commit ToolWindow reports `ChangesView.CommitToolbar`.
- Toolbar visibility therefore recognizes both commit toolbar places and additionally requires `event.isFromActionToolbar`. Keymap, Find Action, and direct shortcut calls remain unaffected.
- This is a runtime-surface conversion of the approved `CommitMessage`-only assumption, not a feature-scope change.

## Constraints

- Kotlin 2.4.0, JDK 21, IntelliJ IDEA 2025.3, and the current Gradle IntelliJ plugin remain authoritative.
- API keys exist only in PasswordSafe. Source context is filtered, capped, and consent-gated with no bypass in v1.
- AI output is atomically written after validation by default; optional preview can be enabled. It is never streamed directly into the commit editor.
- Existing user/worktree changes are preserved. No publish/deploy, DB/SQL, credential disclosure, real-credential provider request, inference request, or source-context transmission is authorized by this task. A credential-free, read-only models-route diagnostic is permitted for protocol verification.

## Prior Task Overlap

- Relationship: `compatible supplement`; RAW-007 and r7 continue the same commit-message helper objective, output contract, Controlled owner, and documentation group while adding a provider and storage/configuration layers.
- Document governance: reuse `dsg:ezcodemark:commit-message-helper-v1` and the existing five-file ledger; r1-r6 evidence remains historical and the r7 net delta received focused plus full current-tree verification.
- Delta: official Codex App Server access, cross-product global synchronization, split shared/private project state, persistent instruction precedence, account-generation consent, focused regressions, current-state synchronization, and reusable failure prevention.

## Requirement Versioning

```yaml
spec_id: ECM-COMMIT-MESSAGE-001
spec_revision: 7
status: implemented-and-verified
raw_sources: [RAW-001, RAW-002, RAW-003, RAW-004, RAW-005, RAW-006, RAW-007]
targets:
  - canonical_manifest: task-local controlled specification
    base_full_version: none
    result_full_version: ECM-COMMIT-MESSAGE-001-r7
delta:
  - requirement_id: CM-001
    operation: add
    before: no commit-message assistant in EzCodeMark
    after: native commit-message assistant with UI, Actions, shortcuts, providers, privacy, and tests
    raw_refs: [RAW-001]
    confirmation: explicit
    canonical_location: this specification
  - requirement_id: CM-002
    operation: amend
    before: provider key field could be cleared by settings polling and model discovery had no session-safe editable selector
    after: full-width session-retained key entry, side-effect-free settings queries, searchable/editable models, and stale/cancelled request protection
    raw_refs: [RAW-002]
    confirmation: explicit
    canonical_location: this specification
  - requirement_id: CM-003
    operation: amend
    before: provider/template pages diverged from the upstream reference; models did not visibly filter; Fetch/Test callbacks stalled in modal settings; AI always opened preview; no reusable style workflow
    after: upstream-aligned layouts/defaults, fuzzy editable models, visible cancellable diagnostics, direct-by-default writeback, Standard/Concise/custom styles, reviewed AI style generation, quick style Action, and packaged Apache-2.0 attribution
    raw_refs: [RAW-003]
    confirmation: explicit
    canonical_location: this specification
  - requirement_id: CM-004
    operation: amend
    before: Generate used platform-specific Ctrl/Command+Alt+Shift+G defaults; Format had no one-off instruction dialog and its service accepted the whole Action snapshot
    after: Generate uses literal C-A-X on all supported keymaps; Format accepts an optional one-off prompt and its service receives only current commit text, never Git context
    raw_refs: [RAW-004]
    confirmation: explicit
    canonical_location: this specification
  - requirement_id: CM-005
    operation: amend
    before: optional preview showed only first/current text and allowed manual editing, Apply, Copy, or Cancel
    after: preview supports confirmed prompt optimization, confirmed Commit refinement, immutable first/initial values, current final result, and session-only operation history with no Git context
    raw_refs: [RAW-005]
    confirmation: explicit
    canonical_location: this specification
  - requirement_id: CM-006
    operation: amend
    before: Profile Edit lost the selected row before opening; the visible model ComboBox editor and selected item could diverge, causing search, selection, and confirmation to fail
    after: toolbar/double-click edit uses a stable view-to-model target; the mounted editable ComboBox synchronizes query, selection, filtering, late Fetch results, and OK commit
    raw_refs: [RAW-006]
    confirmation: explicit
    canonical_location: this specification
  - requirement_id: CM-007
    operation: amend
    before: API-key-only providers, per-IDE application configuration, workspace-only project defaults, and no persistent extra-instruction layer
    after: official Codex App Server ChatGPT subscription access, dual-carrier global synchronization, shared/private project configuration, and bounded global/project extra instructions
    raw_refs: [RAW-007]
    confirmation: explicit
    canonical_location: this specification
memory_used: []
memory_updates:
  - intellij-patched-coroutines-runtime-shadowing
  - intellij-ant-instrumenter-task-race
  - httpurlconnection-response-code-timeout-retry-oom
  - plugin-verifier-multi-ide-first-run-timeout
  - intellij-commit-toolbar-action-place-visibility
  - intellij-configurable-ismodified-secret-side-effects
  - intellij-modal-settings-background-callback-modality
  - intellij-editable-combobox-live-filtering
  - intellij-jtable-refresh-clears-action-selection
  - llm-structured-repair-loses-confirmed-source-request
  - codex-app-server-permission-profile-isolation
  - codex-thread-start-websocket-preconnect
  - plugin-verifier-gradle-offline-metadata-fallback
  - codex-model-list-retention-before-dedup
  - codex-machine-auth-generation-repair
  - portable-quarantine-revision-only-recovery
  - intellij-configurable-apply-stale-baseline
open_questions: []
```

- Merge status: r6 remains accepted historical evidence; r7 is an implemented and verified compatible supplement.
- Actual surface: `actual` for CM-001 through CM-007.
- Requirement-runtime gap: closed; real account login/inference/logout remains intentionally outside automated acceptance.

## Execution Authority And Boundaries

- Control plane and sole decision owner: App Root Thread.
- Read-only agents supplied bounded repository/API reviews; App Root owned every write, risk decision, current-tree verification, documentation synchronization, and acceptance.
- High-risk boundaries remained closed: no production, credential disclosure, publish, DB/SQL, real login/logout/inference, or external service write. A credential-free isolated `thread/start` compatibility smoke triggered an unauthorized websocket preconnect and was not repeated; no turn input or account mutation occurred.
- The generated 2.6GB heap dump was deleted after explicit user authorization; no `.hprof` remains in the repository tree.

## Evidence

- Baseline repository and upstream implementation were inspected before implementation.
- Current source received independent static architecture/privacy reviews; all P0/P1 findings were resolved and Root-reverified.
- The accepted r7 evidence is recorded in [verify.md](verify.md): 4 focused suites / 137 tests, 23 full suites / 259 tests, package/configuration/structure checks, a three-target direct Plugin Verifier matrix, the canonical Gradle three-target matrix, no internal-API report, source review, and documentation closeout.

## TaskExperienceObservation

- Result: r7 implementation and verification are complete. The exact local direct verifier matrix and the recovered canonical Gradle verifier task both passed; the prior metadata-resolution failure remains a verified conditional fallback route, not an active blocker.
- Unavailable dimension: model/token counters remain `usage unavailable`.
- Persistence: Codex isolation/preconnect, bounded model pagination, durable machine-generation repair, portable quarantine recovery, Configurable Apply resnapshot, and Plugin Verifier fallback routes are promoted to [project error memory](../../../knowledge/error-memory/README.md); no global rule/template propagation is needed.
