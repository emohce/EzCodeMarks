# Commit Message Helper Integration — Raw Requirement

Tool: codex
Date: 2026-07-15
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

## Capture Boundary

- Included: material scope, constraints, defaults, privacy/security boundaries, UI/Action behavior, tests, and documentation acceptance.
- Excluded: the full conversation, Agent narration, commands, logs, and intermediate reasoning.
- Audio unavailable or unclear terms: not applicable.
