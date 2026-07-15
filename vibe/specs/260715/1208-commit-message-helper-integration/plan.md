# Commit Message Helper Integration Plan

Tool: codex
Date: 2026-07-15
Task: integrate the commit message helper into EzCodeMark

Documentation level: `controlled`

## Outcome

The planned self-contained commit-message vertical slice, public VCS/Git/Velocity wiring, native UI/Actions/settings, privacy/provider behavior, tests, live UI checks, and documentation closeout are complete.

## Execution Strategy And Result

- Strategy: App Root owned all writes and decisions; bounded read-only agents audited provider, UI, Git, security/privacy, and final architecture surfaces.
- Automation lane: not applicable.
- Duplicate guard: one scoped review per surface; no recursive or overlapping writers.
- Result: all reports were reconciled against the latest tree. Earlier P0/P1 privacy, transport, credential, snapshot, and compatibility findings were fixed before acceptance; final architecture review found no remaining P0/P1.
- Runtime model/token counters were unavailable.

## Execution Topology

| Work Unit | Mode | Scope | Final Evidence | Root Decision |
| --- | --- | --- | --- | --- |
| CM-ROOT | write | implementation, tests, docs, verification | current diff, 119 tests, package and verifier reports, live UI | `accepted` |
| CM-PROVIDER-API | read-only | network, PasswordSafe, Keymap APIs | signatures plus transport/credential regression tests | `accepted` |
| CM-UI-API | read-only | UI DSL, Configurable, commit UI and Action places | fixture tests plus both live commit surfaces | `accepted` |
| CM-GIT-API | read-only | VCS/Git4Idea context and privacy | repository/prompt privacy and cap tests | `accepted` |

## Implemented Change Set

- Added immutable domain models, parser, template/provider/context contracts, request budgets, and privacy policy.
- Added application/workspace persistence, PasswordSafe storage, provider transport, consent, Git context collection, coordinator, and AI orchestration.
- Added four Actions, shortcut declarations, commit-context adapter, structured/additional-requirement/preview dialogs, four settings pages, and four locale bundles.
- Updated Gradle/plugin dependencies, plugin description, README files, user/build/change documentation, technical details, project status, and error memory.
- Kept bookmark ToolWindow/ViewModel/SelectionBus/`.codemark` behavior isolated.

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
- Provider cancellation and retry safety: bounded future-based response wait, deadlines, cancellation, exact fallback classification, UTF-8/SSE tests, and three-request budget pass.
- Source privacy: path/content/binary/generated filtering, consent fingerprints, and hard caps pass.
- Credential persistence: PasswordSafe-only state, profile identity checks, copy/delete semantics, and failure rollback tests pass.
- Live UI variance: both non-modal Commit ToolWindow and traditional Commit Dialog were inspected; the Action-place difference was fixed and regression-tested.

## Verification And Closeout

- All five requested Gradle commands pass on the final tree; exact results are in [verify.md](verify.md).
- Document link audit and `git diff --check` are final closeout gates owned by App Root.
- No rule/template propagation, DB/SQL, deployment, publish, credential disclosure, or external provider request occurred.
- A 2.6GB OOM heap dump remains ignored and untracked because deletion requires explicit user authorization.
