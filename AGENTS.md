# EzCodeMark AI Adapter

Tool: codex

Initialize once:
- Reuse the injected [CodeNote master](../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md), or read it once if it was not injected.
- Read the [project rule index](vibe/rules/README.md) as the project entry.

Load by task signal:
- Read [documentation rules](vibe/rules/documentation.md) for Standard/Controlled, DB/data, deploy-gated, business-changing, documentation-governance, or template-propagation work.
- Read the [process hub](vibe/specs/PROJECT_STATUS.md) for ongoing or overlapping work, Controlled tasks, DB/data, cross-repo, deploy-gated, or migration-linked work.
- From the project index, load only the smallest applicable owner; do not preload route targets or error memory without a matching task, retry, or failure signal.

Hard constraints:
- Keep project-specific rules in `vibe/rules/`; do not copy the CodeNote master into this repository.
- Preserve existing behavior and user changes; do not touch unrelated business code.
- High-risk actions require confirmation: DB writes, deletes, production changes, credentials, publish/deploy, or external service writes.
- Write Markdown links relative to the target document location.
- Final replies must include verification status and memory/process-document status.
