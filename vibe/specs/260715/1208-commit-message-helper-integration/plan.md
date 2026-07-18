# Commit Message Helper Integration Plan

Tool: codex
Date: 2026-07-18
Task: integrate the commit message helper into EzCodeMark

Documentation level: `controlled`

## Outcome

The self-contained commit-message slice remains architecture-isolated. Revision 6 is accepted historical evidence. Revision 7 adds official Codex App Server ChatGPT subscription access plus synchronized global and split shared/private project configuration without weakening existing provider, credential, privacy, or preview behavior.

## Revision 7 Execution Plan

1. Introduce portable-global, common-machine, project-shared, and project-private state owners with explicit schemas and migration from the current application/workspace state.
2. Mirror portable global state between an atomically written JetBrains common-data envelope and a roamable `SettingsCategory.TOOLS` component. Use revision ancestry and optimistic apply; never silently overwrite divergent snapshots.
3. Add an application-scoped Codex process client using stable JSONL stdio methods only: initialize, account, model, thread, turn, interrupt, and notifications.
4. Use a dedicated common Codex home and neutral working directory. Require a supported user-installed binary, keep machine path non-roamable, and leave OAuth/token storage entirely to Codex.
5. Add a `CHATGPT_CODEX` provider capability path with account status, browser/device login, model discovery, reasoning effort, minimal Test, and explicit shared-machine logout.
6. Convert structured requests from a boolean marker to concrete JSON schemas so Codex final messages remain compatible with existing parser/repair/renderer validation.
7. Add project-private profile definitions/selection and project-shared prompt/template/style definitions/defaults. Preserve legacy workspace choices and API-key credentials.
8. Layer built-in instructions, bounded global extra instructions, project inherit/append/replace instructions, style prompt, and one-off operation instructions without allowing configuration to replace security/schema invariants.
9. Move source-context consent to private account-generation-aware state so it does not roam across machines or survive login/account replacement.
10. Add fake-process, sync/conflict, migration, provider, settings, privacy, cancellation, tool-rejection, locale, and platform regressions before the full package/verifier closeout.

Revision 7 performs no real login, inference, source-context transmission, logout, publish, deploy, DB/SQL operation, or credential disclosure during automated execution.

## Execution Strategy And Result

- Strategy: App Root owned all writes and decisions; bounded read-only agents audited provider, UI, Git, security/privacy, and final architecture surfaces.
- Automation lane: not applicable.
- Duplicate guard: one scoped review per surface; no recursive or overlapping writers.
- Result through r7: implementation, review corrections, focused/full tests, packaging, configuration/structure checks, exact three-target compatibility, and documentation/memory closeout completed.
- Runtime model/token counters were unavailable.

## Execution Topology

| Work Unit | Mode | Scope | Final Evidence | Root Decision |
| --- | --- | --- | --- | --- |
| CM-ROOT | write | implementation, tests, docs, verification | 259-test suite, package/config/structure, direct and canonical three-target verifier routes, link/receipt/diff checks | `accepted` |
| CM-PREVIEW-REFINEMENT | write | preview prompt optimization, Commit refinement, history, cancellation/privacy | exact-envelope, source-anchored repair, session, budget, and real coordinator regressions | `accepted` |
| CM-SHORTCUT-FORMAT | write | cross-system Generate shortcut and current-text-only Format prompt | descriptor, prompt, dialog, cancel, repair and no-context regressions | `accepted` |
| CM-PROVIDER-SETTINGS-FIX | write | LLM key/model/Test UI and request lifecycle | mounted editable model control, toolbar/double-click edit, selection/OK, late Fetch, modal callback, cancellation, and PasswordSafe regressions | `accepted` |
| CM-STYLE-AND-TEMPLATE | write | Template/Type/Style layout, safe Velocity, direct/preview writeback | renderer policy, fallback, style prompt and UI lifecycle tests | `accepted` |
| CM-PROVIDER-API | read-only | network, PasswordSafe, Keymap APIs | signatures plus transport/credential regression tests | `accepted` |
| CM-UI-API | read-only | UI DSL, Configurable, commit UI and Action places | fixture tests plus both live commit surfaces | `accepted` |
| CM-GIT-API | read-only | VCS/Git4Idea context and privacy | repository/prompt privacy and cap tests | `accepted` |
| CM-CODEX-PROVIDER | write | App Server process/account/model/turn integration and isolation | 30-client-test protocol suite, provider tests, strict 0.144.5 isolation smoke | `accepted` |
| CM-CONFIG-SYNC-R7 | write | common-data/roaming synchronization and migration | two-JVM CAS, ancestry/divergence, stale-apply, rollback, and payload-boundary tests | `accepted` |
| CM-PROJECT-LAYERS-R7 | write | project shared/private definitions, profiles, credentials, consent, and instructions | platform settings/apply/reset/conflict/precedence tests | `accepted` |

## Implemented Change Set

- Added immutable domain models, parser, template/provider/context contracts, request budgets, and privacy policy.
- Added application/workspace persistence, PasswordSafe storage, provider transport, consent, Git context collection, coordinator, and AI orchestration.
- Added four fixed toolbar Actions plus one separately registered Select Style Action, shortcut declarations, commit-context adapter, structured/additional-requirement/optional-preview dialogs, four settings pages, and four locale bundles.
- Restored upstream-aligned LLM Settings and Commit Template layouts and original Profile/template/type defaults while retaining current EzCodeMarks Settings grouping and architecture.
- Corrected Provider settings with a full-width session-retained masked key, explicit Apply-time clearing, an editable fuzzy-filtered model selector, explicit Active Model control, reliable toolbar/double-click Profile editing, modal-compatible callbacks, request-local cancellation, two-stage Test status, and stale-result guards. The ComboBox now uses public editor/editable setters, immediate query selection, stable popup selection, late-result re-filtering, and explicit `OK` commit.
- Added Standard/Concise/custom styles, project/global selection, live template preview, AI-generated prompt/template proposals with immutable snapshots/review/stale guards, and effective-style propagation through Generate/Format/Smart Echo/repair.
- Made AI writeback direct after validation by default while retaining opt-in editable preview and unchanged-source safeguards.
- Changed Generate's three Keymap declarations to literal `control alt X`; the settings page continues to read the active Keymap and report IDE conflicts dynamically.
- Added a Format-only optional instruction dialog. Cancel occurs before Profile/coordinator/network access; the service accepts only current commit text and preserves the capped one-off instruction through structured repair without collecting Git context.
- Added preview-only iterative refinement. Users can optimize an instruction, review/edit it, confirm the exact primary SYSTEM/USER prompt for both stages, apply a refined result, and inspect/copy session-only first/initial/final and operation history. Follow-up requests send only the current result and validated configuration, share one three-request budget, retain the confirmed source request during repair, and reuse the original coordinator handle.
- Restricted Velocity execution to approved directives/references/string helpers with no file loader, arbitrary member/index access, ranges, or unbounded source/output; invalid project/style/global candidates fall through to the next validated template.
- Packaged the Apache-2.0 license and notice for adapted upstream default template/type text.
- Updated Gradle/plugin dependencies, plugin description, README files, user/build/change documentation, technical details, project status, and error memory.
- Kept bookmark ToolWindow/ViewModel/SelectionBus/`.codemark` behavior isolated.
- Added global schema 3 with atomic common-data CAS plus roamable `TOOLS` state, explicit ancestry/divergence resolution, and no secret/account/consent payload.
- Added VCS-eligible project instructions/templates/styles/defaults and workspace-only project profiles/selections/drafts/consent with namespaced PasswordSafe credentials and compatibility precedence.
- Added Codex CLI 0.144.5+ discovery, a machine-local shared account and auth generation, browser/device login, logout, model discovery, and concrete structured-output completion through stable App Server methods.
- Added collision-resistant deny-by-default permission profiles, isolated home/cwd, effective-response validation, no instructions/history/network/tools, buffered output, tool-event termination, and cancellation that interrupts bound turns or terminates unresolved starts. Client initialization requires the official App Server `experimentalApi=true` capability so the exact active permission profile can be verified before turn input; unsupported or missing evidence fails closed.
- Hardened secondary paths: version probing now shares the isolated process environment, model pagination deduplicates before bounded retention, and invalid/missing auth generations CAS-repair only the raw generation field while preserving unknown properties and rejecting future envelope schemas.
- Revalidate portable content after quarantine even when its revision is unchanged. The 32-entry ancestry horizon remains an explicit fail-safe conflict boundary rather than an unbounded causality claim.
- Resnapshot Project Defaults after a successful partial Apply and cover Defaults, Shared, and Project Providers external-change conflicts without polling or disrupting unsaved controls.

## Architecture Conversion

- The non-modal IDEA 2025.3 toolbar uses `ChangesView.CommitToolbar`, while the traditional dialog uses `CommitMessage`.
- Visibility logic recognizes both only when invoked from an Action toolbar, preserving Keymap and Find Action behavior.
- IDE-bundled Velocity is referenced through the documented `bundledLibrary` workaround because Gradle IntelliJ Platform Plugin 2.16 does not index the 2025.3 module directly.

## Documentation Realization

| Lane | Authority | Acceptance |
| --- | --- | --- |
| Requirement and mechanism | [spec.md](spec.md), this plan | `pass` |
| Raw requirement evolution | [raw-requirement.md](raw-requirement.md), [spec.md](spec.md) | `pass` |
| Execution and failures | [tasks.md](tasks.md), [verify.md](verify.md) | `pass` |
| Current product state | [project status](../../PROJECT_STATUS.md), [README](../../../../README.md), [user guide](../../../../doc/USER_GUIDE.md), [technical details](../../../knowledge/technical-details.md) | `pass` |
| Reusable failure knowledge | [error memory](../../../knowledge/error-memory/README.md) | `pass` |

## Risk Resolution

- Public API variance: compile/fixture checks and three-target Plugin Verifier pass.
- Provider cancellation and retry safety: request-local handles cover queued/in-flight/restart/Apply/Reset/dispose paths; bounded waits, deadlines, exact fallback classification, UTF-8/SSE handling, and the three-request budget remain in force.
- Source privacy: path/content/binary/generated filtering, consent fingerprints, and hard caps pass.
- Credential persistence: PasswordSafe-only state, profile identity checks, copy/delete semantics, side-effect-free `isModified()`, session key retention, and failure rollback tests pass.
- UI variance: fixture/layout/lifecycle tests cover the upstream-aligned structure, native components, full-width key field, mounted editable fuzzy model entry, pre-debounce query synchronization, 344-item selection/confirmation, late Fetch filtering, sorted Profile table toolbar/double-click semantics, style preview, and Action injection. Manual sandbox evidence is recorded only when performed in [verify.md](verify.md).
- Preview privacy/cancellation: immutable prompt envelopes are shared by confirmation and Provider execution; session history has no persistent-state path; fixture tests cover cancel, finish, response-stale, profile-stale, direct-writeback preservation, and no-Git/first-Commit request content.
- Codex account/privacy: token material remains owned by Codex; executable/account generation is machine-local; account mutations are cross-process locked and invalidate consent before mutation; fake-process tests reject isolation drift, tool events, malformed items, stale generation, incomplete login, and unresolved-start cancellation.
- Synchronization: two independent JVM writers prove compare-and-write exclusion; equal/ancestor revisions converge and divergent branches remain explicit until user resolution. Shared project state and private workspace/PasswordSafe state are independently validated.

## Verification And Closeout

- Revision 7 passed 4 focused suites / 137 tests, 23 full suites / 259 tests, package/configuration/structure checks, the exact cached IU-253/IU-261/IU-262 direct Plugin Verifier matrix, and the canonical Gradle IU-253/IU-261/IU-262 matrix; exact results are owned by [verify.md](verify.md).
- Final review removed one internal IntelliJ API use and closed cancellation-before-ID races; the rebuilt artifact has no internal-API report. No P0/P1 implementation finding remains.
- The initial Gradle `verifyPlugin` attempt could not resolve dynamic 2026.2 metadata, so the verified direct CLI fallback used exact cached distributions. The metadata route later recovered and the canonical Gradle task passed the final artifact; the fallback remains documented for future matching failures.
- No rule/template propagation, DB/SQL, deployment, publish, credential disclosure, real login/logout/inference/source-context provider request, or external write is part of r7 acceptance.
- The 2.6GB OOM heap dump was deleted after explicit user authorization and is no longer a residual item.
