# EzCodeMark AI Collaboration Governance Requirements

Tool: codex
Project requirement version: `R1`
Updated: 2026-07-18
Parent Spec: [SPEC-260710-1636 W62](../../../CzzProj/CodeNote/vibe/specs/260710/1636-codex-execution-journal-evolution/spec.md#L1)

## Shared Authority

- Codex 与 OpenCode 在 EzCodeMark 中加载同一个 CodeNote [VibeAi master](../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/VibeAi.md#L1)、本项目入口和按信号选择的 cold owner；不得复制第二套规则、需求版本、验证或记忆协议。
- 宿主差异只作为实际 capability 报告。Hook、Skill、Agent、approval 或 runtime 支持的差异不能改变共同的规则优先级、安全门禁、证据和最终接纳标准。
- 跨项目算法与全局记忆保留在 CodeNote；EzCodeMark 只保存项目 stack、路径、业务风险、验证命令和本地接纳结果。

## Project Authority

- [AGENTS.md](../../AGENTS.md#L1) 与 [project rule entry](../rules/README.md#L1) 是短加载入口；[documentation rules](../rules/documentation.md#L1) 路由过程、需求和 closeout。
- 本 [Requirement Manifest](README.md#L1) 与本文件是 AI collaboration governance 的 canonical authority；[PROJECT_STATUS](../specs/PROJECT_STATUS.md#L1) 只保存当前投影和父任务反链。
- commit-message helper integration r7 继续由其既有 Spec/verification 持有。R1 不修改应用业务、IDE 行为、配置格式或发布范围。

## Project Identity

- CodeNote [canonical project catalog](../../../CzzProj/CodeNote/vibe/knowledge/project-index.json#L1) 中的稳定 Project ID 与 Repository ID 均为 `ez-code-mark`，该 Repository 是当前 default Repository。
- 真实 clone 路径、host anchor、hostname aliases 与 Repository Instance ID 只存在于 ignored per-host profile；不得复制到本仓库文档、CodeNote tracked catalog 或验证回执。
- Project lookup、Repository lookup 和 path reverse lookup 使用 CodeNote 的唯一 resolver；既有 project/path/route 输出保持兼容，Repository/Instance 字段为增量输出。

## Verification And Gates

- 本地接纳至少要求 EzCodeMark project audit、requirement/current route links、scoped diff hygiene 和 CodeNote 父任务的 working active-version tuple 通过；该 tuple 必须实际读取本 Manifest 的 R1、`canonical_members` 及每个 canonical member 版本，不能只接受次目标语法。
- CodeNote 的文档同步回执不能证明本仓库内容；EzCodeMark 的 project audit、link、diff 和 readback 必须在父任务 closeout 时重新执行。
- R1 未授权外部 Codex/OpenCode config、Home/Hook/Automation、应用业务、DB、发布、凭据/权限、stage、commit 或 push。
