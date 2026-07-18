# EzCodeMarks

An IntelliJ IDEA plugin for structured code bookmarks and assisted Git commit authoring. It combines code tours, Markdown notes, and editor integration with a structured/AI-assisted commit workflow while keeping both feature areas isolated in storage and runtime state.

[简体中文](README.md)

## Main Capabilities

### CodeMarks and guided navigation

- Organize project knowledge with Bookmark, Group, Process, and DescriptiveBookmark nodes.
- Search, reorder, inspect Markdown details, and step through flows in the tool window.
- Link the tree and editor through gutter icons, line-end hints, and bidirectional selection.
- Store bookmark data in `.codemark/codemark.json`, ready to version when desired.

### Git commit message assistant

- Compose structured messages with `type`, `scope`, `subject`, `body`, `BREAKING CHANGE`, `Closes`, and `skip ci`.
- Generate from included Git changes, or optimize only the current commit text with an optional one-off instruction and no Git diff collection.
- Apply validated AI results directly by default, with an optional review-before-write setting; cancellation, failure, and validation errors preserve the original message.
- In review-before-write mode, refine the result again with AI, optionally optimize the one-off instruction, confirm the exact SYSTEM/USER prompts, and inspect the complete in-session refinement history.
- Manage Velocity templates, commit types, Standard/Concise/custom commit styles, and multiple OpenAI Compatible, Anthropic, or ChatGPT/Codex provider profiles.
- Keep API keys in IntelliJ PasswordSafe, leave ChatGPT login and tokens entirely to the user-installed Codex CLI, and gate filtered, size-limited source context behind consent.
- Use English, Simplified Chinese, Japanese, or Korean Action, settings, validation, and error resources.

## Requirements

- IntelliJ IDEA 2025.3+ (since build 253)
- JDK 21
- Kotlin 2.4.0
- IntelliJ Platform Gradle Plugin 2.16.0
- Git4Idea (bundled with the IDE)
- Optional: `codex-cli 0.144.5+` for the ChatGPT/Codex provider

## Development and Installation

```bash
git clone <repository-url>
cd EzCodeMark
./gradlew runIde
```

Build the installable plugin:

```bash
./gradlew buildPlugin
```

The ZIP is written to `build/distributions/` and can be installed through `Settings | Plugins | Install Plugin from Disk…`. See the [build guide](doc/build-guide.md) for the complete packaging and verification flow.

## Using the Commit Message Assistant

Four stable Actions are registered in the commit-message area and the Keymap group:

| Action | Shown by default | Windows / Linux | macOS |
| --- | --- | --- | --- |
| Create Commit Message | Yes | `Ctrl+Alt+Shift+M` | `⌘⌥⇧M` |
| Generate Commit Message | Yes | `Ctrl+Alt+X` | `⌃⌥X` |
| Generate With Additional Requirements | Yes | Unassigned | Unassigned |
| Format Commit Message | No | Unassigned | Unassigned |

Toolbar visibility only affects the Commit Message place. Hidden Actions remain available from their shortcuts and Find Action. A running Action changes to a cancel icon and can be triggered again to cancel; its three siblings are temporarily disabled for the same commit document.

`Format Commit Message` first accepts an optional one-off optimization instruction. Blank input uses the current Commit Style and Cancel sends no request. Format sends only the current commit text, template, style, and one-off instruction; it does not read or send Git status, diff, unversioned files, revision, or recent commits.

With review-before-write enabled, choose `Refine with AI…` on the result page. Each accepted operation keeps the first Commit, initial AI result, current final result, before/after text, entered instruction, AI-optimized instruction, user-confirmed instruction, and confirmed prompt envelopes. `History` can inspect or copy the complete record. This history exists only for the current preview session and is never written to plugin XML, workspace state, `.codemark`, logs, or analytics. A refinement sends only the current result, confirmed instruction, template, and style—never the first Commit or Git context—and shares a maximum of three Provider requests.

`Select Commit Style` is available through Find Action, Keymap, and `Tools | EzCodeMarks | Git Commit Message`. It has no default shortcut and does not occupy the commit toolbar.

Configuration is under `Settings | Tools | EzCodeMarks | Git Commit Message`:

- `Git Commit Message`: toolbar visibility, current Keymap/conflicts, field visibility, type display, skip-ci, optional review before AI writeback, and cross-product global extra instructions/conflict resolution.
- `Commit Template`: upstream-aligned Template / Type / Style tabs with template and type management, safe Velocity validation/live preview, style preview, and AI-generated prompts/templates from a natural-language style description.
- `LLM Settings`: an upstream-aligned active-model/global-options header, Profile table, and Profile dialog. The API-key field is full-width and session-retained; the editable model selector performs live fuzzy filtering; Fetch models and Test are cancellable and expose staged status. This page also configures the Codex executable, browser/device-code sign-in, account refresh, and shared-machine logout.
- `Project Private`: workspace-only template/style selection and unfinished drafts.
- `Project Providers`: workspace-only profiles, active selection, and PasswordSafe credentials, with safe fallback to the global active profile.
- `Project Shared`: VCS-eligible extra instructions, templates, styles, and shared defaults. Project instructions can inherit, append to, or replace global extra instructions.

`Test` first lists models, then performs a minimal inference capped at 8 output tokens only when a model is selected. With a blank model it stops after discovery and asks for a selection. The UI clearly warns that the operation can make billable Provider requests.

When no AI profile is configured, the Actions provide a recoverable link to the relevant provider settings. ChatGPT/Codex profiles use no API key: EzCodeMarks talks to the official stable App Server in a dedicated Codex home and creates a fresh structured thread with no history, project instructions, project/home filesystem access, or network-capable model tools.

## CodeMarks Quick Start

Use these shortcuts in the editor:

| Shortcut | Action |
| --- | --- |
| `Shift+F2` | Create or edit a CodeMark on the current line |
| `Shift+F3` | Create a group |
| `Shift+F4` | Create a note |
| `F1` | Show details for the hovered/selected tree node |
| `Shift+F1` | Show details for the CodeMark at the caret |
| `Alt+Shift+↓` / `Alt+Shift+↑` | Next / previous CodeMark |
| `Shift+Delete` | Delete the CodeMark on the current line |

Open the tool window through `View | Tool Windows | EzCodeMarks`. The tree supports drag-and-drop, search, Markdown details, and project-relative file/line links. Press `F2` in a Description or Markdown field to open the detached editor.

## Architecture and Data Boundaries

The bookmark feature keeps its existing Repository, ViewModel, SelectionBus, and ToolWindow architecture. The commit assistant is a separate `commitmessage` vertical slice:

| Layer | Responsibility | Entry |
| --- | --- | --- |
| Domain | Structured models, parsing, template/provider contracts, context policy | [CommitMessageModels.kt](src/main/kotlin/emohce/domain/commitmessage/CommitMessageModels.kt#L5) |
| Data | State, PasswordSafe, Velocity, provider HTTP, Git context, coordination | [CommitMessageAiService.kt](src/main/kotlin/emohce/data/commitmessage/CommitMessageAiService.kt#L22) |
| Presentation | VCS Actions, commit-context adapter, dialogs, settings, bundles | [CommitMessageActions.kt](src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L42) |

The commit assistant does not use the Bookmark ToolWindow, BookmarkViewModel, SelectionBus, or `.codemark`. Global settings use both an atomic JetBrains common-data snapshot and roamable `TOOLS` state; divergent branches require explicit resolution. Shared project definitions use `.idea/ezCodeMarkCommitMessage.xml`, while private profiles, selections, drafts, and consent remain in workspace state. API keys stay in PasswordSafe. The Codex path, account generation, and dedicated home are machine-local, and OAuth tokens remain owned by Codex.

OpenAI Compatible and Anthropic traffic honors the IDE proxy, uses a 15-second connection timeout and a cancellable 120-second read limit; the isolated Codex App Server owns ChatGPT transport. Git context filters binary/generated files plus `.env*`, credentials, keys, certificates, SSH, service-account, and secret paths/content. Version 1 has no bypass. ChatGPT login, account replacement, and logout invalidate prior source-context consent.

## Verification

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPluginProjectConfiguration
./gradlew verifyPluginStructure
./gradlew verifyPlugin
```

Tests cover structured parsing/rendering, Velocity, dual-carrier synchronization/conflicts, shared/private project state, PasswordSafe, Codex App Server isolation/cancellation/tool rejection, context filtering/caps, provider SSE/fallbacks, and Action, shortcut, DataKey, and settings lifecycles.

## Documentation

- [User guide](doc/USER_GUIDE.md)
- [Build guide](doc/build-guide.md)
- [Change log](doc/change-log.md)
- [Bookmark tree operations specification](doc/260604-cursor-tree-operations-spec.md)
- [Current project status](vibe/specs/PROJECT_STATUS.md)

## License and Attribution

This project is licensed under the terms in [LICENSE](LICENSE). Third-party text attribution is documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The CodeMarks feature draws inspiration from [CodeTour](https://github.com/LefterisXris/CodeTour) and [Bookmark-X](https://github.com/Nonoas/Bookmark-X). The commit assistant is an architectural rewrite inspired by the Apache-2.0 [Git Commit Message Helper](https://github.com/AutismSuperman/git-commit-message-helper). Adapted default Velocity template text and commit-type descriptions retain the upstream Apache-2.0 license in the distribution; its Swing `.form`, reflection, raw Git/HTTP, and plaintext-secret implementation were not copied.
