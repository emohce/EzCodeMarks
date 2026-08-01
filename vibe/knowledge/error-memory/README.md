# Error Memory Index

Tool: codex

Use this directory for reusable, verified failure patterns.

## Verified Records

- [IntelliJ patched coroutines runtime shadowing](intellij-patched-coroutines-runtime-shadowing.md)
- [IntelliJ Ant instrumenter task race](intellij-ant-instrumenter-task-race.md)
- [`HttpURLConnection.responseCode` timeout retry OOM](httpurlconnection-response-code-timeout-retry-oom.md)
- [Plugin Verifier multi-IDE first-run timeout](plugin-verifier-multi-ide-first-run-timeout.md)
- [IntelliJ commit toolbar Action-place visibility](intellij-commit-toolbar-action-place-visibility.md)
- [IntelliJ Configurable `isModified()` secret side effects](intellij-configurable-ismodified-secret-side-effects.md)
- [IntelliJ modal settings background callback modality](intellij-modal-settings-background-callback-modality.md)
- [IntelliJ editable ComboBox live filtering](intellij-editable-combobox-live-filtering.md)
- [IntelliJ JTable refresh clears action selection](intellij-jtable-refresh-clears-action-selection.md)
- [LLM structured repair retains the confirmed source request](llm-structured-repair-loses-confirmed-source-request.md)
- [Codex App Server permission-profile isolation](codex-app-server-permission-profile-isolation.md)
- [Codex `thread/start` websocket preconnect](codex-thread-start-websocket-preconnect.md)
- [Plugin Verifier Gradle offline metadata fallback](plugin-verifier-gradle-offline-metadata-fallback.md)
- [Codex model-list retention before deduplication](codex-model-list-retention-before-dedup.md)
- [Codex machine auth-generation repair](codex-machine-auth-generation-repair.md)
- [Portable quarantine revision-only recovery](portable-quarantine-revision-only-recovery.md)
- [IntelliJ Configurable Apply stale baseline](intellij-configurable-apply-stale-baseline.md)
- [Project shared settings unsupported schema](project-shared-settings-unsupported-schema.md)
- [Environment Action legacy random revision CAS loop](environment-action-legacy-random-revision-cas-loop.md)
- [Snapshot process descendants before closing stdin](process-tree-snapshot-before-stdin-close.md)
- [IntelliJ project Configurable requires a Java-visible constructor](intellij-project-configurable-java-constructor.md)
- [IntelliJ ToolWindow approvals must remain inline and stoppable](intellij-toolwindow-inline-approval.md)
- [Open IntelliJ Settings by Configurable class](intellij-show-settings-configurable-class.md)
- [Discover the native Commit editor across focused and non-modal surfaces](intellij-commit-editor-discovery.md)
- [IntelliJ Computer Use smoke must use a real app shell](intellij-plugin-smoke-bare-runide-host.md)
- [IntelliJ Swing popup can collapse the accessibility bridge](intellij-swing-popup-accessibility-bridge-loss.md)

## Rules

- Record symptom, wrong assumption, verified root cause, evidence, prevention rule, and latest applicable path.
- Do not store unverified guesses or sensitive data.
- Recall only records whose frontmatter status and Alternative Route are both `verified`.
