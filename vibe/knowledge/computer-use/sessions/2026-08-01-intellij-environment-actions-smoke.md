---
schema: computer-use-session/v1
session_id: ezcodemark-20260801-intellij-environment-actions-smoke
status: completed
recording_fidelity: reconstructed-partial
project: ezcodemark
task_ref: ../../../specs/260731/1720-environment-actions-codex-chat/verify.md
route_id: ezcodemark-intellij-plugin-smoke
host: macos-codex-computer-use
application: IntelliJ IDEA
surface: Environment Actions / Codex Chat / native Commit
started_at: null
ended_at: null
privacy: sanitized
---

# 2026-08-01 IntelliJ Environment Actions Smoke

## Fidelity Notice

This record was reconstructed after the user introduced the mandatory per-call project ledger. The accepted task verification, route evidence and known failures preserve material behavior, but the original raw method count, timestamps, element indices, coordinates and several exact method boundaries are unavailable. Those gaps are explicit below; this file must not be treated as `live-complete`.

Future Computer Use starts with a live session file and records every method before the next call, as required by the [project session index](README.md#recording-gate).

## Preflight

- Goal: validate the true multi-turn Codex Chat, permission/Skills/plugins display, inline approval, Stop, New conversation, Configurable transactions/navigation, native Prepare Commit and disposer cleanup in a real IntelliJ host.
- Dedicated-interface boundary: automated tests and fake App Server protocol already covered logic, but visible IntelliJ Settings/ToolWindow/Commit interaction required GUI evidence.
- Route selected: [real IntelliJ app-shell sandbox](../jetbrains-plugin-smoke.md#L1).
- Sandbox: genuine installed IntelliJ app bundle; isolated config/system/log; actual Gradle `prepareSandbox` plugin directory; disposable Git project; ignored fake Codex CLI/App Server.
- Allowed impact: local disposable settings/project state and fake-runtime process activity.
- Forbidden impact: normal user IntelliJ settings/plugins, real Codex credentials/model/source transmission, file staging/Commit, publish/deploy or external write.
- Cleanup assertions: normal IDE exit, disposer/connection close, no fake CLI/IDE process, exact test-created common-data Environment removed from the live path.

## Reconstructed Event Ledger

| seq | at | method | normalized_input | pre_state | result | post_state | assertion | decision | impact | evidence_ref |
| ---: | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | time-unavailable | `gap` | original locked-session Computer Use calls unavailable | macOS session locked | original method results unavailable | attachment unavailable | no runtime assertion | wait for user unlock; do not substitute real account/model/Commit | none | [task ledger](../../../specs/260731/1720-environment-actions-codex-chat/tasks.md#execution-journal) |
| 2 | time-unavailable | `gap` | exact application-discovery/attach call count unavailable | unlocked; bare Gradle `runIde`/Java host | original method results unavailable | no stable actionable Computer Use surface | host identity failed | abandon equivalent retries | none | [bare-host failure](../../error-memory/intellij-plugin-smoke-bare-runide-host.md#L1) |
| 3 | time-unavailable | `supporting-event` | prepare sandbox, render isolated IDEA properties, launch genuine app shell and disposable project; exact shell inputs intentionally not copied | failed bare-host route | support preparation succeeded | real IntelliJ application/accessibility identity available | sandbox/host identity advanced | continue through verified app-shell route | local ignored fixture only | none retained |
| 4 | time-unavailable | `gap` | initial `get_app_state` exact app argument/tree unavailable | real isolated IntelliJ instance open | original method result unavailable | plugin ToolWindow/Settings surface observed | plugin host loaded | proceed to Settings transaction lane | none | none retained |
| 5 | time-unavailable | `gap` | Settings navigation/click methods and element indices unavailable | plugin UI visible | original method results unavailable | Codex CLI Configurable open; normal Interactive CLI and isolated Commit Provider identities visible | identity boundary passed | test unapplied executable | navigation only | none retained |
| 6 | time-unavailable | `gap` | exact `set_value`/click/state sequence unavailable; dummy path content omitted | Codex CLI Settings open | original method results unavailable | account operations disabled before Apply; Cancel restored persisted state; Apply persisted fake executable | Apply/Reset/Cancel passed | continue | isolated settings only | none retained |
| 7 | time-unavailable | `gap` | exact ToolWindow click/state methods unavailable | Settings lane complete | original method results unavailable | Environment Actions and Codex CLI buttons opened their typed Configurable pages | navigation passed | continue to Chat | navigation only | [runtime verification](../../../specs/260731/1720-environment-actions-codex-chat/verify.md#local-runtime-host-results) |
| 8 | time-unavailable | `gap` | first Send action and state calls unavailable; prompt text excluded | Chat idle; fake CLI configured | original method results unavailable | one fake App Server process/thread started; `fake reply 1` rendered | thread/start + first turn passed | send second turn in same session | local fake runtime | none retained |
| 9 | time-unavailable | `gap` | second Send action/state calls unavailable; prompt text excluded | same thread idle | original method results unavailable | `fake reply 2` rendered without a new thread | true multi-turn passed | continue | local fake runtime | none retained |
| 10 | time-unavailable | `gap` | approval event observation and Deny click indices unavailable | fake local-command approval pending | original method results unavailable | inline redacted approval shown; Deny returned `decline`; turn completed | approval remains user-controlled and Stop visible | continue | local fake runtime; no real command | none retained |
| 11 | time-unavailable | `gap` | slow-turn Send, Stop and follow-up state indices unavailable | Chat idle | original method results unavailable | Stop sent `turn/interrupt`; UI showed `interrupted`; Send re-enabled | interrupt passed | continue | local fake runtime | none retained |
| 12 | time-unavailable | `gap` | New conversation click/state indices unavailable | prior thread completed/interrupted | original method results unavailable | transcript/policy reset; old process closed; new process/thread counter restarted | lifecycle passed | continue to native Commit | local fake runtime | none retained |
| 13 | time-unavailable | `gap` | native Commit navigation, action and Replace element indices unavailable | visible non-modal Commit editor had non-empty text | original method results unavailable | Replace / Append / Cancel shown; explicit Replace changed only message to safe fixture text | IDE owns final Commit side effect | do not click Commit/stage/select files | local editor text only | [runtime verification](../../../specs/260731/1720-environment-actions-codex-chat/verify.md#local-runtime-host-results) |
| 14 | time-unavailable | `gap` | exact ComboBox click/state sequence unavailable | isolated Settings popup lane | original method results unavailable | Swing popup caused AX tree and screenshot channel loss | observation bridge failed | invalidate indices; use one pre-seeded/keyboard fallback, no blind retry | none | [Swing popup failure](../../error-memory/intellij-swing-popup-accessibility-bridge-loss.md#L1) |
| 15 | time-unavailable | `press_key` | malformed base key plus separate modifier shape; exact raw object omitted | editable control focused | method returned without the intended chord effect | literal `q` entered instead of Quit | cleanup shortcut failed and mutated local field | stop malformed route; use exact declared key schema once | isolated UI edit | [global key-shape failure](../../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/memory/error-archive/2026-08-01-computer-use-key-chord-parameter-shape.md#L1) |
| 16 | time-unavailable | `press_key` | app=`IntelliJ IDEA`, key=`super+q` | isolated IntelliJ active | success | native Exit path opened | exact key schema passed | handle one native confirmation | local app lifecycle | none retained |
| 17 | time-unavailable | `gap` | Exit confirmation click/state index unavailable | native Exit confirmation visible | original method result unavailable | application closed normally | disposer path exercised | verify processes and common data | local app lifecycle | none retained |
| 18 | time-unavailable | `supporting-event` | process/common-data checks; exact commands omitted | app closed | support checks succeeded | no fake CLI/IDE process; fixture logged connection close; test Environment removed from live common data and retained only in ignored recovery fixture | cleanup passed | close session | no external side effect | none retained |

## Outcomes

- Runtime assertions: accepted. Exact evidence remains in [task verification](../../../specs/260731/1720-environment-actions-codex-chat/verify.md#local-runtime-host-results).
- Missing evidence: original per-method count, exact ordering within grouped gaps, timestamps, app arguments, element indices, coordinates and full state summaries. No values were fabricated.
- External effects: none. No real login/logout, inference, source transmission, staging, Commit, publish/deploy or external write.
- Cleanup: accepted; normal exit, process closure and exact test-created common-data cleanup recorded.
- Retained artifacts: only ignored local sandbox/fake-runtime fixtures needed for reproducibility; no screenshot, raw accessibility tree, prompt, credential or absolute host path is retained here.

## Extraction Decision

- `project_route`: verified and retained in [IntelliJ plugin smoke route](../jetbrains-plugin-smoke.md#L1).
- `project_error`: bare-host and Swing-popup fingerprints verified; implementation-specific runtime failures remain separately indexed.
- `global_extraction`: equivalent-retry, exact key-shape and route-memory-versus-session-ledger corrections accepted in CodeNote; this event table remains project-local.
