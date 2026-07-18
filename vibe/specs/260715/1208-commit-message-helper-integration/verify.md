# Commit Message Helper Integration Verification

Tool: codex
Date: 2026-07-18
Task: integrate the commit message helper into EzCodeMark

Documentation level: `controlled`

## Final Commands

```text
./gradlew test --console=plain
./gradlew buildPlugin --console=plain
./gradlew verifyPluginProjectConfiguration --console=plain
./gradlew verifyPluginStructure --console=plain
./gradlew verifyPlugin --console=plain
```

For the r6 gate, `test`, `buildPlugin`, project configuration, and structure checks ran in one invocation; `verifyPlugin` then ran separately against the same code tree. Focused provider-interaction tests and explicit `javap` inspection preceded the final gate. Documentation-only closeout edits followed and were checked by the link, receipt, residue, and diff gates below.

The initial r7 gate ran focused/full/package/configuration/structure checks, but Gradle `verifyPlugin` could not resolve its dynamic 2026.2 dependency because the environment temporarily had no metadata route. Plugin Verifier CLI 1.409 therefore verified the artifact directly against exact local IU-253.33813.25, IU-261.26222.65, and IU-262.8665.176 distributions. After metadata recovery and residual P2 hardening, the final tree reran 4 focused suites, the complete suite, packaging/configuration/structure checks, the canonical Gradle matrix through IU-262.8665.258, and a fresh direct `.176` matrix in `build/reports/pluginVerifier-r7-p2-final`. Both routes used the rebuilt final artifact and passed; the conditional fallback remains recorded in [project error memory](../../../knowledge/error-memory/plugin-verifier-gradle-offline-metadata-fallback.md).

## Automated Results

| Gate | Result | Evidence |
| --- | --- | --- |
| `test` | PASS | 23 suites, 259 tests, 0 failures, 0 errors, 0 skipped |
| Focused r7 regressions | PASS | 4 suites, 137 tests: 30 Codex App Server, 14 portable-store, 26 state/coordinator, and 67 platform integration tests; 0 failures/skips |
| State/coordinator regressions | PASS | 26 tests covering portable semantics, same-revision quarantine recovery, durable/CAS machine-generation repair, future-schema rejection, project precedence, migration, consent payload boundaries, and coordinator lifecycle |
| Historical r6 provider-interaction gate | PASS | 2 suites, 48 tests: mounted editable editor, immediate query selection, 344-model filtering/selection/OK, custom IDs, late Fetch filtering, sorted Profile toolbar/double-click editing; current behavior is included in the 259-test suite |
| Historical r4 shortcut/Format gate | PASS | 45 tests, 0 failures; current behavior is included in the 259-test suite |
| Focused preview-refinement regressions | PASS | exact prompt envelopes, session/history, shared budget, source-anchored repair, and real coordinator cancel/finish/response-stale cases |
| `buildPlugin` | PASS | [EzCodeMarks-1.2.0.zip](../../../../build/distributions/EzCodeMarks-1.2.0.zip), approximately 4.1MB |
| `verifyPluginProjectConfiguration` | PASS | no configuration error |
| `verifyPluginStructure` | PASS | plugin archive structure accepted |
| Gradle `verifyPlugin` | PASS | canonical task resolved IU-253.33813.25, IU-261.26222.65, and IU-262.8665.258; all compatible; no internal-API report |
| Direct exact-target Plugin Verifier | PASS | CLI 1.409 offline against IU-253.33813.25, IU-261.26222.65, and IU-262.8665.176; all compatible; no internal-API report |
| Packaged third-party attribution | PASS | plugin JAR contains `META-INF/THIRD-PARTY-NOTICES.txt` and the full upstream Apache-2.0 license |
| Locale bundle parity | PASS | English, simplified Chinese, Japanese, and Korean key sets match; no unresolved placeholder key |
| Repository residue scan | PASS | no `.hprof`, credential dump, or tracked temporary transfer artifact remains |
| Markdown code-link audit | PASS | final changed process/current Markdown files report zero issues |
| Documentation Sync Receipt | PASS | controlled group recorded after the final P2-hardening document, memory, link, rule, and diff validation freeze |
| `git diff --check` | PASS | no whitespace error |
| ComboBox bytecode | PASS | `FilterableModelComboBox` constructor invokes public `setEditor` and `setEditable`; no inherited-field initialization bypass |

### Plugin Verifier Matrices

| Route | IDE | Verdict | Non-blocking API notices |
| --- | --- | --- | --- |
| Direct CLI 1.409 | IU-253.33813.25 | Compatible | 7 deprecated, 6 experimental usages |
| Direct CLI 1.409 | IU-261.26222.65 | Compatible | 11 deprecated, 6 experimental usages |
| Direct CLI 1.409 | IU-262.8665.176 | Compatible | 11 deprecated, 6 experimental usages |
| Gradle `verifyPlugin` | IU-253.33813.25 | Compatible | 7 deprecated, 6 experimental usages |
| Gradle `verifyPlugin` | IU-261.26222.65 | Compatible | 11 deprecated, 6 experimental usages |
| Gradle `verifyPlugin` | IU-262.8665.258 | Compatible | 11 deprecated, 6 experimental usages |

The notices are existing/legacy IntelliJ API usage reports and do not contain compatibility errors. The six experimental notices are inherited `ToolWindowFactory` usages and are unrelated to Codex App Server capability negotiation. Dynamic enable/disable remains eligible. An initial r7 matrix exposed one new `PluginManagerCore.getPlugin` internal-API call used only for client version labeling; it was replaced with the JAR package implementation version, and both final rebuilt matrices contain no internal-API report.

The final P2-hardening direct verifier invocation completed all three `.176`-matrix targets in 9.025 seconds of verifier work with zero downloads. The canonical Gradle task completed the `.258` matrix in 1 minute 39 seconds of verifier work. The existing [multi-IDE timeout route](../../../knowledge/error-memory/plugin-verifier-multi-ide-first-run-timeout.md) was not the active failure class.

### Codex App Server Capability Boundary

- `initialize` advertises the official App Server `experimentalApi=true` capability solely because effective isolation acceptance requires `activePermissionProfile` in the `thread/start` response.
- The capability is mandatory for this integration: unsupported negotiation, a missing profile, or an unexpected profile produces an isolation failure and process termination before `turn/start` or user input.
- This does not enable model tools or relax sandboxing, and it is independent of the JetBrains Plugin Verifier experimental-API notices above.

## Coverage Summary

- Structured parsing/rendering, exact upstream defaults, types/skip-ci, safe Velocity directives/references/helpers/resource policy, output caps, project/style/global/built-in template fallback, and state migration.
- Coordinator cancellation, same-document mutual exclusion, per-project/document isolation, stale modification-stamp/text rejection, and request budget.
- Stable Action IDs/order, non-popup group, both commit toolbar places, scoped visibility, Keymap/Find Action availability, literal `control alt X` Generate declarations across default/macOS Keymaps, conflict detection, DataKey priority, and EDT snapshots.
- Format's optional dialog/cancel boundary, current-text-only service signature, 16K current text/8K template/4K one-off prompt caps, absence of Git-context sections, one-off instruction retention across JSON repair, and repeat-trigger cancellation without reopening the dialog.
- Preview refinement retains immutable first/initial/current values and successful operation evidence in dialog memory only. Tests cover raw/AI-optimized/confirmed instructions, optimizer notes/envelopes, complete history copy, exact confirmation-to-Provider primary envelopes, no first-Commit/Git-context content, shared three-request budget, and source-anchored JSON repair.
- Real coordinator fixtures cover cancel before Provider access, finish before preparation, and response-stale rejection after one Provider call; the current result and history are unchanged for cancelled interactions. Profile fingerprint changes are rejected before requests.
- Settings `apply/reset/isModified/dispose`, optimistic conflict detection, project defaults/drafts/styles, four locale key sets, and PasswordSafe-only serialization with copy/delete/failure behavior. Provider regressions cover side-effect-free `isModified()`, masked retention after Apply and failed Apply, pre-Apply keys used by Fetch Models, pending clear without stored-key fallback, modal-safe callbacks, two-stage Test, request-local cancellation, stale model/profile results, the mounted Aqua editable editor, immediate query-to-selection synchronization, delayed fuzzy filtering, popup selection stability, custom/late-fetched model confirmation, and sorted view/model Profile editing from both toolbar and double-click.
- Portable global schema 3, atomic UTF-8 replace, symlink/path rejection, UUID ancestry, equal/ancestor convergence, explicit divergence resolution, stale Apply rejection, bounded/cancellable cross-process locks, failure rollback, and a real two-JVM compare-and-write race in which exactly one writer succeeds.
- VCS-eligible project instructions/templates/styles/defaults plus workspace-only profiles/selection/drafts/consent, project credential namespacing, global fallback, legacy private-selection precedence, settings Apply/reset/conflict behavior, and `INHERIT`/`APPEND`/`REPLACE` instruction composition under built-in invariants.
- Codex CLI version discovery under the same isolated environment as app-server startup, isolated machine settings/account generation, browser/device login lifecycle, logout `null` payload, cross-process account-operation lock, pre-mutation consent invalidation, failed-mutation client discard, auth-generation client rollover, forward-compatible durable generation repair with CAS-winner adoption, account/model parsing, early ordered deduplication with page/raw/cursor/retained bounds, concrete structured schemas, and text-model filtering.
- Codex allowlisted process environment with isolated XDG/temp roots, strict JSONL ordering and typed booleans, randomized deny-by-default permission profile, mandatory experimental capability negotiation, exact active-profile/cwd/sandbox/ephemeral/instruction-source validation, disabled history/environment/MCP/web/shell/browser/delegation paths, item lifecycle and resource bounds, malformed/unknown/tool event termination, buffered final output, one bounded restart, and terminal cancellation/timeout that interrupts bound turns or terminates the process tree.
- PasswordSafe credential generation, non-Codex consent, inference snapshots, Settings Test/Fetch, Apply rollback, and portable/project state changes are serialized through the matching in-process and cross-process transaction guards; failed Apply restores the exact prior credentials.
- Portable quarantine revalidates current content even when a repaired payload retains the same revision; both snapshot and roaming `loadState` recovery clear failure only after validation. The 32-entry ancestry horizon has an explicit fail-safe conflict characterization.
- Project Defaults resnapshots persisted values after successful partial Apply. Defaults, Shared, and Project Providers reject external same-field/profile/state conflicts while leaving local controls modified; continuous polling is intentionally unnecessary.
- OpenAI-compatible/Anthropic JSON and SSE, split UTF-8, header/body timeout, cancellation, authentication/rate-limit behavior, reasoning/streaming fallback, JSON repair, endpoint derivation, and error redaction.
- Source-context consent, binary/generated/sensitive path and content filtering, recent-message filtering, fixed caps, and unversioned aggregate budget.

## IntelliJ UI Acceptance Evidence

| Surface / Scenario | Evidence | Observation |
| --- | --- | --- |
| Non-modal Commit ToolWindow | earlier r1 live + current fixtures | Create, Generate, Generate With Context in required order; Format hidden by default; `ChangesView.CommitToolbar` retained |
| Traditional Commit Dialog | earlier r1 live + current fixtures | `CommitMessage` ordering/visibility and native toolbar integration retained |
| Keymap / Find Action / Tools | current fixtures and descriptor checks | four fixed Action IDs remain single-registered; Select Style is separate and project-aware |
| Structured Create / optional preview | current component/fixture tests | required structured fields, subject validation, direct-by-default writeback, and opt-in preview contracts pass |
| Preview AI refinement / history | current component/contract fixtures | Refine and History actions, exact prompt confirmation, editable optimized instruction, first/initial/final evidence, complete copy, shared budget, and cancel/stale guards pass |
| LLM Settings | current IntelliJ/Aqua fixture, mounted-editor, event-dispatch, result, and bytecode tests | upstream-like global header/Profile table/dialog, full-width masked key, toolbar/double-click editing, mounted fuzzy model input, pre-debounce selection, 344-model choice/OK, custom IDs, late Fetch filtering, modal-safe Fetch/Test, cancellation, Codex executable/account controls, and status behavior pass |
| Project Shared / Project Providers / Project Private | current platform fixture and state tests | VCS-eligible definitions/defaults/instructions remain separate from workspace Profiles/selection/drafts/consent and PasswordSafe credentials; Apply/reset/conflict/rollback/precedence behavior passes |
| Commit Template / Type / Style | current fixture and domain tests | upstream-like tabs/defaults, live preview, Standard/Concise/custom styles, AI proposal guards, and safe fallback pass |
| Format one-off optimization | current component/contract fixtures | dedicated optional prompt, trim/cancel behavior, current-text-only request contract, no Git context, and direct/preview writeback guards pass |
| UI architecture | source/descriptor/fixture checks | native controls; no new ToolWindow, `.form`, custom visual system, or internal Darcula component |

The earlier live run exposed the IDEA 2025.3 non-modal place `ChangesView.CommitToolbar`; the implementation and current regression suite cover it together with traditional `CommitMessage`. The earlier r2 sandbox run remains historical provider-page evidence, but the user's r6 report superseded its model/edit interaction conclusion. No new r3-r7 screenshot-level sandbox acceptance is counted. Current r7 UI acceptance rests on mounted IntelliJ/Aqua fixture components, direct toolbar/mouse/selection/confirmation event tests, state/protocol fixtures, bytecode inspection, and source review.

## Intentionally Unperformed External Checks

- No real credential was entered or used for r7. No real ChatGPT login, logout, inference, turn input, source-context request, or account mutation was performed. One credential-free isolated `thread/start` compatibility smoke caused Codex CLI 0.144.5 to attempt an unauthorized websocket preconnect and receive 401 before `turn/start`; live checks stopped and the prevention route is recorded in [error memory](../../../knowledge/error-memory/codex-thread-start-websocket-preconnect.md). The earlier credential-free read-only OpenRouter `/api/v1/models` diagnostic remains r2 protocol evidence.
- No Marketplace publish/deploy, production change, DB/SQL operation, or credential read occurred.
- The 2.6GB `java_pid59324.hprof` generated during the earlier fixed OOM failure was deleted after explicit user authorization; a final repository search found no `.hprof` file.

## Review And Acceptance

- Independent privacy/implementation review initially identified material filtering, transport, credential, fallback, snapshot, and UI-place gaps; each was fixed and regression-tested.
- The first r6 provider-interaction review found two P1 gaps: inherited Swing fields could bypass public ComboBox setters, and typed text was not selected before debounce. Explicit setters, immediate selection synchronization, mounted-editor and pre-debounce assertions, and late-Fetch filtering were added. The second review found no P0/P1; its two P2 test-hardening suggestions were also added and passed.
- The r7 review found four P1 and two P2 gaps across effective Codex permissions/tool events, logout payload, account-mutation failure handling, cross-process portable writes, and post-Apply consent behavior. All were corrected with regressions. Final post-review hardening additionally covered environment isolation, strict protocol types and bounds, item lifecycle, process-tree termination, auth-generation client rollover, timed portable locks, credential transactions, exact rollback, Settings pending-clear/stale-callback behavior, cancellation before thread/turn IDs, and removal of the only new internal IntelliJ API use. No P0/P1 remains.
- Residual P2 review closed version-probe environment drift, duplicate-heavy model retention, non-durable/forward-destructive generation repair, revision-only quarantine recovery, stale Project Defaults baselines, and missing project Configurable conflict coverage. Independent follow-up review found no remaining concrete P0/P1/P2 defect in the edited surfaces.
- App Root inspected the final source and verifier evidence, accepted review findings only after current-tree focused/full tests and the rebuilt exact three-target matrix, and owns the final decision: `accepted`.

## Requirement Integration

- Canonical backlinks: [raw requirement](raw-requirement.md), [specification](spec.md), [plan](plan.md), and [tasks](tasks.md).
- Version/status: `ECM-COMMIT-MESSAGE-001-r7`, `implemented-and-verified`.
- All canonical/current targets synchronized: yes.
- Evidence label: `actual`.
- Prior task overlap: compatible supplement to the existing Controlled task and `dsg:ezcodemark:commit-message-helper-v1`; r1-r6 remain historical evidence and the r7 storage/provider/privacy delta was independently reverified.

## Documentation And Memory Decision

- Documentation impact: `requirement-canonical + project-current + verification-ledger`; synchronized and covered by the final content-addressed receipt.
- Memory updates: seventeen verified project records in the [error-memory index](../../../knowledge/error-memory/README.md). Final r7 additions cover bounded model pagination, durable machine-generation repair, same-revision quarantine recovery, and Configurable post-Apply resnapshot; the existing verifier and Codex isolation routes remain distinct.
- Rule/template/soul propagation: none.
- DB/SQL memory: not applicable.
- Runtime token/model usage: `usage unavailable`.
- Residual implementation blocker: none. The canonical Gradle verifier route recovered and passed; the exact-cache direct route remains a conditional fallback for a future matching metadata failure.

## Residual Hardening Considerations

- Portable ancestry retains at most 32 revision IDs. A genuinely linear branch beyond that horizon can require explicit conflict resolution; the behavior is fail-safe and covered by a characterization test.
- Static path checks plus `NOFOLLOW_LINKS` cannot remove every parent/lock symlink TOCTOU against a hostile same-account filesystem mutator. Closing that theoretical boundary requires a versioned native or descriptor-relative storage redesign and is outside the cooperative local-process threat model.

## Rule Declaration

- Global/project entry and Controlled documentation route: applied.
- Sidecar: main thread for r7 final review/closeout; prior read-only review evidence remained subordinate to App Root.
- High-risk gates: remained closed.
