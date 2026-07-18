# Commit Message Helper Integration — Raw Requirement

Tool: codex
Date: 2026-07-15
Last updated: 2026-07-17
Spec: [spec.md](spec.md)
Source format: `chat-requirement-summary`
Capture fidelity: `normalized-material-requirement`

## RAW-001

```yaml
raw_id: RAW-001
captured_at: 2026-07-15T12:08:00+08:00
state: active
source_lineage: user-approved integration plan in the current task
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Reimplement the upstream Git commit message helper inside EzCodeMark using the current Kotlin/JDK 21/IntelliJ 2025.3 architecture.
- Keep the feature independent from bookmark state, ToolWindow, ViewModel, SelectionBus, and `.codemark` storage.
- Add four stable VCS commit-message Actions, scoped toolbar visibility, two default shortcuts, Keymap discovery/conflict reporting, cancellation, and per-project/per-document task isolation.
- Provide structured editing, templates and types, AI generation with optional additional requirements, AI formatting, Smart Echo, previews, multiple OpenAI-compatible/Anthropic profiles, PasswordSafe secrets, privacy consent, and bounded Git context.
- Add native JetBrains settings pages and dialogs, plus English, Simplified Chinese, Japanese, and Korean feature resources.
- Preserve the original commit message on cancellation, error, invalid output, or rejected preview.
- Use VCS/Git4Idea/Velocity and public IntelliJ APIs; do not copy upstream Swing forms, reflection, raw Git processes, raw HTTP behavior, plaintext credentials, custom icons, or global loading state.
- Add automated coverage for domain behavior, state, providers, filtering, cancellation, Actions/shortcuts/settings, and mock HTTP; run the full Gradle verification set.
- Maintain Controlled process documentation and synchronize README, user guidance, project status, and technical details.

## RAW-002

```yaml
raw_id: RAW-002
captured_at: 2026-07-16
state: active
source_lineage: user-reported AI Providers regression and UI preference in the current task
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Fetch Models reports a missing API key after the key is entered because the field clears itself.
- The API-key input is visually too short and must be adapted to the full settings layout.
- Keep the newly entered key masked and visible for the whole settings session, including after Apply.
- Make the model selector both searchable as a dropdown and editable for custom model IDs.
- Delete the generated heap dump if it is no longer needed.

## RAW-003

```yaml
raw_id: RAW-003
captured_at: 2026-07-16
state: active
source_lineage: user-provided upstream screenshots and behavior corrections in the current task
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Restore the upstream LLM Settings and Commit Template layout hierarchy and original defaults while retaining EzCodeMarks architecture, Settings grouping, public APIs, PasswordSafe, and Kotlin UI components.
- Make model typing perform visible live fuzzy filtering while preserving arbitrary custom model IDs.
- Make Fetch Models and Test visibly respond, expose staged status, and support real cancellation. Test must list models first and perform only a minimal inference when a model is selected.
- Apply generated/formatted AI results directly to the commit editor by default; keep editable preview as an opt-in setting and preserve the original text on cancellation, error, invalid output, or stale source.
- Add reusable Standard, Concise, and custom commit styles. Users can describe a style in natural language and ask the configured model to propose the style prompt and Velocity template, then review the proposal before applying it.
- Provide a quick project-level style selector through Find Action, Keymap, and Tools without adding it to the fixed four-Action commit toolbar group.
- Preserve adapted upstream default template/type text with the corresponding Apache-2.0 attribution and full license in the distributable.

## RAW-004

```yaml
raw_id: RAW-004
captured_at: 2026-07-16
state: active
source_lineage: user-requested cross-platform shortcut and one-off Format optimization increment
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Change the default Generate shortcut to the literal cross-system `C-A-X` combination: `Ctrl+Alt+X` on Windows/Linux and `Control+Option+X` on macOS.
- Let Format accept an optional one-off AI instruction before optimization; cancellation must not start a request.
- Format must optimize only the current commit message and must not collect or send Git status, diff, unversioned content, revision, or recent commits.
- Keep the configured Commit Style, direct/optional-preview writeback, request cancellation, three-request budget, and stale-document protection.
- Do not persist the one-off Format instruction; reusable behavior remains the responsibility of Commit Style.

## RAW-005

```yaml
raw_id: RAW-005
captured_at: 2026-07-16
state: active
source_lineage: user-requested iterative optimization on the AI result page
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Add an optimization-prompt entry to the editable AI result/preview page.
- Retain the first commit text, the initial AI result, the current final result, each user modification prompt, and the related successful commit-modification operations.
- Let AI optimize the user's modification prompt, while keeping the optimized prompt editable and requiring user confirmation before it can modify the commit result.
- Show the exact expanded SYSTEM/USER prompt template that will be sent for prompt optimization and commit refinement; a request starts only after confirmation.
- Keep this history in the current preview-dialog session only. Do not persist commit text, prompts, expanded templates, or operation history to application/workspace state, `.codemark`, logs, or analytics.
- Later refinement sends only the current commit, confirmed instruction, validated template, and configured style; it never sends the first commit or Git status/diff/files/revision/history.
- Preserve optional-preview behavior: direct writeback remains the default, and iterative optimization appears only when review-before-write is enabled.

## RAW-006

```yaml
raw_id: RAW-006
captured_at: 2026-07-17
state: active
source_lineage: user-reported LLM Profile interaction failures with screenshots
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Make the Profile edit toolbar action open the selected configuration reliably.
- Make the fetched model dropdown genuinely editable, searchable, selectable by mouse or keyboard, and confirmable after scrolling.
- Preserve the selected or custom model through filtering and the Profile dialog `OK` action.
- Double-clicking a valid Profile table row must select that row and open the same edit dialog; single-click, right-click, and blank-area double-click must not edit.

## RAW-007

```yaml
raw_id: RAW-007
captured_at: 2026-07-17
state: active
source_lineage: user-requested ChatGPT subscription provider and confirmed global/project configuration decisions
privacy_boundary: no-verbatim-prompt-or-transcript
```

This is a normalized semantic record; verbatim fidelity is intentionally unavailable.

- Add ChatGPT Plus/Pro subscription access through the official Codex App Server protocol rather than copying OpenCode's OAuth client, callback server, token handling, or private HTTP transport.
- Use one machine-local ChatGPT account shared by EzCodeMark across installed JetBrains IDE products. Keep it isolated from the user's normal Codex CLI/VS Code account and never expose OAuth tokens to EzCodeMark state.
- Share portable global commit-message configuration across JetBrains products even when Backup and Sync is disabled, and also make it eligible for JetBrains Backup and Sync across products and machines.
- Synchronize no API key, OAuth token, account email, authorization code, executable path, or other machine-specific/secret material.
- Split project configuration: prompts, styles, templates, and shared defaults may be committed with the project; provider profiles, account selection, credentials, drafts, and personal overrides remain private workspace state.
- Add bounded persistent extra instructions at global and project scope. Project instructions can inherit, append to, or replace global extra instructions, but cannot replace the built-in structured-output, factuality, privacy, or safety contract.
- Let a project select or define its own private provider profile while preserving safe fallback to the global active profile and all existing OpenAI-compatible/Anthropic behavior.
- Run Codex requests as isolated ephemeral structured-output turns with no project instruction loading, no project/home filesystem access, no persisted conversation history, and immediate failure if a tool execution is attempted.
- Reuse the existing searchable model control, cancellation/stale-result protections, source-context consent, optional preview/refinement, localization, and full verification gates.
- Automated verification must not perform a real OAuth login, inference request, source-context transmission, logout, or other external account write.

## Capture Boundary

- Included: material scope, constraints, defaults, privacy/security boundaries, UI/Action behavior, tests, and documentation acceptance.
- Excluded: the full conversation, Agent narration, commands, logs, and intermediate reasoning.
- Audio unavailable or unclear terms: not applicable.
