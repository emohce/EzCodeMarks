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
- Generate from included Git changes, add extra requirements, format an existing draft, or use Smart Echo.
- Review and edit every AI result before applying it; cancellation, failure, and validation errors preserve the original message.
- Manage Velocity templates, commit types, and multiple OpenAI Compatible or Anthropic provider profiles.
- Keep API keys in IntelliJ PasswordSafe and gate filtered, size-limited source context behind per-endpoint consent.
- Use English, Simplified Chinese, Japanese, or Korean Action, settings, validation, and error resources.

## Requirements

- IntelliJ IDEA 2025.3+ (since build 253)
- JDK 21
- Kotlin 2.4.0
- IntelliJ Platform Gradle Plugin 2.16.0
- Git4Idea (bundled with the IDE)

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
| Generate Commit Message | Yes | `Ctrl+Alt+Shift+G` | `⌘⌥⇧G` |
| Generate With Additional Requirements | Yes | Unassigned | Unassigned |
| Format Commit Message | No | Unassigned | Unassigned |

Toolbar visibility only affects the Commit Message place. Hidden Actions remain available from their shortcuts and Find Action. A running Action changes to a cancel icon and can be triggered again to cancel; its three siblings are temporarily disabled for the same commit document.

Configuration is under `Settings | Tools | EzCodeMarks | Git Commit Message`:

- `Git Commit Message`: toolbar visibility, current Keymap/conflicts, field visibility, type display, skip-ci, and Smart Echo.
- `Templates & Types`: template lifecycle, global default, Velocity validation/live preview, and ordered type descriptions.
- `AI Providers`: active profile, protocol, endpoint, model, temperature, language, streaming, reasoning compatibility, connection testing, and model discovery.
- `Project Defaults`: project template override, global-default restore, and unfinished-draft clearing.

When no AI profile is configured, the Actions provide a recoverable link to provider settings. Project drafts and template overrides use IDE workspace state and never enter `.codemark`.

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

The commit assistant does not use the Bookmark ToolWindow, BookmarkViewModel, SelectionBus, or `.codemark`. Application preferences use IDE configuration storage, project template/draft state uses workspace storage, and API keys are stored only by PasswordSafe.

Provider traffic honors the IDE proxy, uses a 15-second connection timeout and a cancellable 120-second read limit. Git context filters binary/generated files plus `.env*`, credentials, keys, certificates, SSH, service-account, and secret paths/content. Version 1 has no bypass.

## Verification

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPluginProjectConfiguration
./gradlew verifyPluginStructure
./gradlew verifyPlugin
```

Tests cover structured parsing/rendering, Velocity, state migration, the PasswordSafe boundary, context filtering/caps, providers/SSE/cancellation/fallbacks, and Action, shortcut, DataKey, and settings lifecycles.

## Documentation

- [User guide](doc/USER_GUIDE.md)
- [Build guide](doc/build-guide.md)
- [Change log](doc/change-log.md)
- [Bookmark tree operations specification](doc/260604-cursor-tree-operations-spec.md)
- [Current project status](vibe/specs/PROJECT_STATUS.md)

## License and Attribution

This project is licensed under the terms in [LICENSE](LICENSE).

The CodeMarks feature draws inspiration from [CodeTour](https://github.com/LefterisXris/CodeTour) and [Bookmark-X](https://github.com/Nonoas/Bookmark-X). The commit assistant is a behavior-level redesign inspired by the Apache-2.0 [Git Commit Message Helper](https://github.com/AutismSuperman/git-commit-message-helper), implemented independently for the current EzCodeMarks Kotlin/JDK 21/IntelliJ 2025.3 architecture. Its Swing `.form`, reflection, raw Git/HTTP, and plaintext-secret implementation were not copied.
