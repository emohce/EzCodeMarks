# Environment Actions / Codex Chat Controlled Specification

## Status

- Requirement ID: `ECM-ENV-CODEX-CHAT-001`
- State: implemented and accepted; automated and local fake-runtime acceptance passed
- Relationship to Commit Message r7: compatible supplement; strict commit-provider isolation remains authoritative
- Task root: `vibe/specs/260731/1720-environment-actions-codex-chat/`

## Execution Authority

- Control plane: `app-root`
- Sole decision owner: App Root Thread
- Allowed interactive execution surfaces: `main`, `native-thread`
- Automation lane: `not-applicable`
- Surface-to-surface delegation: forbidden
- Main-owned decisions: scope, architecture, user communication, risk approval, repository writes, reconciliation, final acceptance

## Canonical Behavior

- Commit Provider keeps its dedicated Codex home/account, fresh ephemeral inference thread, strict effective permission validation, no project instruction loading, no tool execution, and no bookmark-state coupling.
- Interactive Chat starts an ephemeral App Server thread in the selected project/environment cwd while inheriting the user's normal Codex home and configuration. It exposes the effective permission state, streams events, surfaces approval requests, permits one active turn, and interrupts/closes on user request or disposal.
- A broad inherited permission profile requires one acknowledgement per fresh session. The plugin never fabricates an approval decision.
- Saved `CODEX` Actions remain one-shot jobs. They use structured non-interactive output, pass prompts through stdin, and require an explicit opt-in before bypassing a non-Git-directory check.
- Schema v2 keeps machine-local shared Environment definitions and stable slot IDs, adds revision/default/order fields, and stores active Environment in project-private non-roaming state. Concurrent common-store writes use compare-and-swap and never silently replace a conflicting revision.
- Legacy `GIT_COMMIT` values migrate to `PREPARE_COMMIT`. Invocation opens the native Commit workflow and offers safe message insertion; no file selection, staging, raw Git process, or final commit occurs in the plugin.
- ToolWindow content owns a disposer; long-running process and App Server resources terminate with the content/project. User-visible text is localized in the existing English, Simplified Chinese, Japanese, and Korean set.
- Codex executable configuration uses native Configurable Apply/Reset/Cancel. Interactive and isolated identities are separately labelled; immediate credential operations require explicit confirmation and are not represented as reversible settings edits.

## Compatibility And Safety

- Public IDs `EzCodeMarks.EnvironmentAction.Slot1` through `Slot10` remain unchanged.
- `CodexAppServerClient.complete()` and all four Commit Message Actions remain behavior-compatible.
- Chat history and App Server threads are not persisted by EzCodeMarks.
- Environment variables remain plaintext non-secret data and are redacted from logs/notifications.
- Automated verification performs no real OAuth/account mutation, inference, Git commit, deployment, publication, or external-service write.

## Acceptance

- Focused and full tests cover schema migration, ordering, project selection, concurrent storage, App Server sessions/approvals/cancellation, native Commit preparation, settings transactions, localization, descriptor registration, disposal, and process termination.
- `git diff --check`, XML parsing, Gradle tests, project configuration, plugin structure, and the configured Plugin Verifier matrix pass.
- Manual host verification uses fake/local protocol fixtures and separately records multi-turn, approval, Stop, New conversation, settings and native Commit UI evidence.
- Task docs, project status, user guide, change log, environment interface document, and applicable commit r8 supplement match the accepted implementation.

## Acceptance Result

- The final current-tree suite passed 29 suites / 309 tests with zero failure, error or skip. Project configuration, plugin structure, XML and the three-target Plugin Verifier matrix also pass.
- Plugin Verifier reports Compatible for IU-253.33813.55, IU-261.26222.65 and IU-262.9437.65. Its deprecated/experimental notices point to pre-existing Bookmark/ToolWindow surfaces; it reports no internal API and no compatibility problem in the new Environment/Chat/Commit classes.
- IntelliJ 2026.1.4 host acceptance passed with a local fake App Server: one thread carried multiple turns; permissions/Skills/plugins rendered; inline approval could be denied; Stop interrupted a running turn; New conversation closed the old process and reset state; both Settings buttons selected the correct Configurable; project close left no fake process.
- `PREPARE_COMMIT` reused the visible native non-modal Commit editor, displayed Replace / Append / Cancel for non-empty text and changed only the editor message after explicit Replace. No file selection, staging, Commit action, raw Git, real login, inference, source transmission or external write was performed.
