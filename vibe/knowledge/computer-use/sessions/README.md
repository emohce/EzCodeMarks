# EzCodeMark Computer Use Session Index

Tool: codex
Schema: [`computer-use-session/v1`](../../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/memory/computer-use-routes/session-record-v1.md#L1)

This directory stores complete, sanitized project-level Computer Use execution evidence. It is separate from reusable [route memory](../jetbrains-plugin-smoke.md#L1) and [error memory](../../error-memory/README.md#L1).

## Sessions

| Session | Fidelity | Route | Outcome | Global Extraction |
| --- | --- | --- | --- | --- |
| [2026-08-01 IntelliJ Environment Actions smoke](2026-08-01-intellij-environment-actions-smoke.md#L1) | `reconstructed-partial` | `ezcodemark-intellij-plugin-smoke` | accepted runtime assertions; exact historical per-call sequence unavailable | route/retry/key-shape/app-shell/Swing-popup lessons linked |

## Recording Gate

1. Create a session file before the first Computer Use method and set `status: running`, `recording_fidelity: live-complete` provisionally.
2. After every Computer Use method returns, append one event before invoking the next method. Multiple methods inside one host-tool call still receive separate rows.
3. Record failures, confirmations, external/local impact, fallback, stop/interruption and cleanup in actual order.
4. Redact sensitive/prompt-like text; summarize state instead of copying raw accessibility trees or screenshots.
5. On close, downgrade to `reconstructed-partial` if any call, ordering, parameter or result is missing. Link the session from the active Task verification when applicable.
6. Separately update the reusable route/error memory only when the session proves a material delta. The session itself is always retained at project level.
