<!-- codenote-local-context:conditional-v3 -->
# Project context

Project-owned conditional detail. Edit this local owner for project-specific facts; global policy stays in the compact core. Commands and inline paths are relative to the repository root unless their original text says otherwise. Read the sections relevant to the affected surface before material work.

## Project context from AGENTS.md

# EzCodeMark AI Adapter

- Read the [project rule index](<README.md>) as the project entry.

Load by task signal:
- Read [documentation rules](<documentation.md>) for Standard/Controlled, DB/data, deploy-gated, business-changing, documentation-governance, or template-propagation work.
- Read the [process hub](<../specs/PROJECT_STATUS.md>) for ongoing or overlapping work, Controlled tasks, DB/data, cross-repo, deploy-gated, or migration-linked work.
- From the project index, load only the smallest applicable owner; do not preload route targets or error memory without a matching task, retry, or failure signal.

Hard constraints:
- Keep project-specific rules in `vibe/rules/`; do not copy the CodeNote master into this repository.
- Preserve existing behavior and user changes; do not touch unrelated business code.
- High-risk actions require confirmation: DB writes, deletes, production changes, credentials, publish/deploy, or external service writes.
- Write Markdown links relative to the target document location.

## Project context from vibe/rules/README.md

# EzCodeMark AI Rules

## Initialization

- This file is the project entry. Links below are task routes, not an initialization preload list.
- Start with the smallest applicable owner and add another only when a distinct task signal or global guard requires it.

## Task Routes

- Project constraints: [project.md](<project.md>), for source, configuration, business behavior, or project-risk work.
- Commands and verification: [workflow.md](<workflow.md>), before running project commands or selecting checks.
- Knowledge routing: [knowledge.md](<knowledge.md>), when reusable project facts, ADRs, technical knowledge, or memory need lookup or synchronization.
- Documentation routing: [documentation.md](<documentation.md>), for Standard/Controlled, DB/data, deploy-gated, business-changing, documentation-governance, or template-propagation work.
- Requirement authority: [Requirement Manifest](<../requirements/README.md>), for EzCodeMark AI-governance requirement lookup or synchronization.
- Process hub: [PROJECT_STATUS.md](<../specs/PROJECT_STATUS.md>), for ongoing or overlapping work, Controlled tasks, DB/data, cross-repo, deploy-gated, or migration-linked work.
- Matching error memory: [project error-memory index](<../knowledge/error-memory/README.md>), only before repeating a known failed route or when the current symptom/fingerprint matches; load only the matching record.
- Error capture: [error-memory-capture](<../../../CzzProj/CodeNote/AiRef/VibePractice/Skills/global/error-memory-capture/SKILL.md>), only after a verified reusable failure, user correction, repeated failed approach, DB/dataFix incident, or tool/runtime trap.

## Rule Boundary

- CodeNote stores cross-project AI collaboration rules.
- This project stores only project-specific stack, commands, paths, business rules, risk areas, and verification notes.
- Legacy AI rules are preserved under `vibe/knowledge/legacy/` when replaced by this structure.

## Task Closeout

- Verification performed or skipped with reason.
- When the error-capture trigger above applies, route it through `error-memory-capture` to the project error-memory index before closeout; otherwise do not preload the Skill or archive.
