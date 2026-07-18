# EzCodeMark Project Status

Tool: codex
Date: 2026-07-18

## Purpose

Compact process hub for active AI work. This file routes current tasks to project docs without storing durable rules.

## Rule Links

- Project documentation: [../rules/documentation.md](../rules/documentation.md)
- Project knowledge: [../knowledge/README.md](../knowledge/README.md)
- AI-governance requirements: [../requirements/README.md](../requirements/README.md)
- Global process rules: [../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/process/rules.md#3-project-location)

## Current Focus

- Status: commit-message helper integration r7 is implemented and accepted. The r6 baseline remains intact; r7 adds official Codex App Server ChatGPT subscription access, dual global synchronization, split shared/private project configuration, and persistent extra instructions.
- Latest task docs: [specification](260715/1208-commit-message-helper-integration/spec.md), [plan](260715/1208-commit-message-helper-integration/plan.md), [tasks](260715/1208-commit-message-helper-integration/tasks.md), [verification](260715/1208-commit-message-helper-integration/verify.md), and [handoff](260715/1208-commit-message-helper-integration/handoff.md).
- Requirement authority: [raw requirement](260715/1208-commit-message-helper-integration/raw-requirement.md) and the implemented task specification.
- Verification: the final r7 tree passed 4 focused suites / 137 tests and 23 full suites / 259 tests, package/configuration/structure checks, Plugin Verifier CLI 1.409 against exact IU-253.33813.25, IU-261.26222.65, and IU-262.8665.176 targets, and canonical Gradle verification against IU-253.33813.25, IU-261.26222.65, and IU-262.8665.258. All six verdicts are compatible and the rebuilt artifact has no internal-API report.
- Verifier routing: the earlier Gradle dynamic-metadata failure is no longer active; the recovered canonical route passed. The verified exact-cache direct route remains a conditional fallback in [Plugin Verifier Gradle offline metadata fallback](../knowledge/error-memory/plugin-verifier-gradle-offline-metadata-fallback.md).
- Codex compatibility boundary: client initialization requires the official App Server `experimentalApi=true` capability solely to validate the exact `activePermissionProfile`; missing or mismatched isolation evidence rejects before turn input.
- Latest hardening: version probing shares the isolated environment; model pages deduplicate before bounded retention; machine auth-generation repair is forward-compatible and CAS-stable; portable quarantine revalidates same-revision repairs; Project Defaults resnapshots after partial Apply and all project Settings conflict paths are covered.
- Open implementation gates: none. Real ChatGPT login, logout, inference, and source-context transmission remain intentionally outside automated acceptance.
- Cleanup: the authorized 2.6GB heap dump was deleted; no `.hprof` remains.
- Latest reusable failure routes: [Codex permission-profile isolation](../knowledge/error-memory/codex-app-server-permission-profile-isolation.md), [Codex thread-start websocket preconnect](../knowledge/error-memory/codex-thread-start-websocket-preconnect.md), and [Plugin Verifier Gradle offline metadata fallback](../knowledge/error-memory/plugin-verifier-gradle-offline-metadata-fallback.md).
- Latest hardening routes: [bounded model pagination](../knowledge/error-memory/codex-model-list-retention-before-dedup.md), [machine generation repair](../knowledge/error-memory/codex-machine-auth-generation-repair.md), [portable quarantine recovery](../knowledge/error-memory/portable-quarantine-revision-only-recovery.md), and [Configurable Apply resnapshot](../knowledge/error-memory/intellij-configurable-apply-stale-baseline.md).
- Reused and reverified route: [multi-IDE Plugin Verifier first-run timeout](../knowledge/error-memory/plugin-verifier-multi-ide-first-run-timeout.md).
- Latest refinement failure route: [structured JSON repair retains the confirmed source request](../knowledge/error-memory/llm-structured-repair-loses-confirmed-source-request.md).
- AI-governance R1: accepted through the CodeNote [W62/R29 parent](../../../CzzProj/CodeNote/vibe/specs/260710/1636-codex-execution-journal-evolution/spec.md#L1). EzCodeMark now owns a local Requirement Manifest/canonical route while Codex/OpenCode continue to share CodeNote's tool-neutral rule and evidence authorities; no application behavior or external configuration changed.

## Governance Baseline

- Template propagation: accepted global baseline; EzCodeMark keeps only project-specific routes and does not copy mother-board rules.
- Codex evolution: `v3-route-accepted`; no r7 Hook, supervisor, or rollout change. Product use of Codex App Server is an EzCodeMark runtime integration, not a CodeNote runtime-rule change.
- Rule Task Trace: accepted global baseline; r7 changes product behavior, current documentation, and project error memory only.
- `w24-primary-objective-continuity-accepted`: primary user work remains ahead of advisory governance lanes.
- `w28-documentation-impact-accepted`: requirement-canonical, project-current, verification-ledger, and project-memory synchronization are closed for r7.
- `w30-standard-requirement-owner-accepted`: Standard requirement ownership remains raw requirement plus the complete Spec owner; this task continues its existing Controlled owner.
- `w62-project-repository-instance-r1-accepted`: Project/Repository identity resolves through the shared CodeNote catalog/resolver, machine-local Instance/host values remain ignored, and this project retains only the parent backlink, local R1 result, verification and residual gates.

## Update Triggers

- Update when current focus, active task docs, verification state, risk gates, sibling links, or memory routing changes.
- Do not add rows solely for memory or rule housekeeping; update the target rule or knowledge index instead.
