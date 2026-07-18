# Documentation Rules

Tool: codex
Date: 2026-06-22

## Purpose

Define EzCodeMark-specific documentation routing. Cross-project documentation and process rules stay in CodeNote; this file maps those rules onto this repository.

## Authoritative Sources

| Layer | Location | Role |
| --- | --- | --- |
| Global master | [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md) | Cross-project AI workflow, safety, memory, verification, and documentation rules. |
| Process layout authority | [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md#3-project-location) | Task date grouping, task folder layout, and flat-folder repair. |
| DB governance | [../../../CzzProj/CodeNote/DevelopRef/调试工具/db/governance/README.md](../../../CzzProj/CodeNote/DevelopRef/调试工具/db/governance/README.md#5-workspace-shape-and-naming) | AI-DB workspace shape, storage routing, and naming authority. |
| Project adapter | [../../AGENTS.md](../../AGENTS.md) | Short tool routing surface. |
| Project rules | [README.md](README.md), [project.md](project.md), [workflow.md](workflow.md), [knowledge.md](knowledge.md), [documentation.md](documentation.md) | EzCodeMark stack, risk boundaries, verification, and local documentation routing. |
| Process hub | [../specs/PROJECT_STATUS.md](../specs/PROJECT_STATUS.md) | Current focus, active task docs, verification status, and open gates. |
| Requirement authority | [../requirements/README.md](../requirements/README.md), [../requirements/ai-governance.md](../requirements/ai-governance.md) | EzCodeMark AI-collaboration Manifest and canonical requirement; product requirements stay with their existing task owners. |
| Project knowledge | [../knowledge/README.md](../knowledge/README.md) | Reusable project facts, ADR/error-memory indexes, and technical details. |

## Project Mapping

- Keep reusable cross-project rules in CodeNote; keep only EzCodeMark-specific stack, commands, paths, and risk boundaries in this repository.
- Task/archive folder date grouping and flat-folder repair are not redefined here; follow the global process layout authority above.
- Legacy or historical docs remain evidence until promoted into [../knowledge/README.md](../knowledge/README.md) or linked from current process docs.
- DB workspace is not configured; if DB/data work becomes active, initialize `vibe/ai-db/` through the DB governance authority above.

## Process Contract Routing

- [Process rules](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md) solely own `Quick`, `Standard`, `Standard requirement`, and `Controlled`. Legacy `L0/L1`, `L2`, and `L3/L4` labels are compatibility vocabulary only and never force a level.
- `Standard non-requirement` routes to its Task Card; `Standard requirement` routes to `raw-requirement.md + spec.md`, with the Spec owner holding the complete requirement evidence. Controlled routes to the globally defined five owners.
- Communication and final-response behavior routes to [process/communication-io.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/communication-io.md); rollout and runtime supervision route to [codex-evolution/rollout/README.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/codex-evolution/rollout/README.md) and [codex-evolution/runtime-supervision/README.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/codex-evolution/runtime-supervision/README.md). This project adapter does not duplicate those algorithms.

## Closeout

- Update [../specs/PROJECT_STATUS.md](../specs/PROJECT_STATUS.md) when current focus, active task docs, verification state, gates, sibling links, or memory routing changes.
- Promote reusable conclusions to [../knowledge/README.md](../knowledge/README.md), ADR/error-memory equivalents, project rules, or DB memory when applicable.
- Report verification, memory routing, and process document status in final delivery.
