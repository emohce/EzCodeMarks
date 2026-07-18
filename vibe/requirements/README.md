# EzCodeMark Project Requirement Manifest

Tool: codex
Updated: 2026-07-18

```yaml
project_identity: ez-code-mark
manifest_scope: repository-ai-collaboration-governance
project_requirement_version: R1
canonical_members:
  - path: ai-governance.md
    scope: ai-collaboration-governance
spec_index: ../specs/README.md
last_integrated_spec: SPEC-260710-1636-codex-execution-journal-evolution
```

## Authority

- 本目录是 EzCodeMark 仓库 AI 协作治理范围的唯一 Requirement Manifest；应用产品需求和 commit-message helper 功能需求仍由其既有任务权威持有，不并入本 Manifest。
- 当前 R1 由 CodeNote 父任务 [SPEC-260710-1636 W62](../../../CzzProj/CodeNote/vibe/specs/260710/1636-codex-execution-journal-evolution/spec.md#L1) 初始化；EzCodeMark 不创建重复的 W62 子任务或中央 registry 行。
- 确认后的后续净变化必须回写 canonical member，并在 [PROJECT_STATUS](../specs/PROJECT_STATUS.md#L1) 保留父任务反链、本地结果、验证和残余门禁。

## Canonical Members

| Scope | Current Authority | Revision | Latest Spec |
| --- | --- | --- | --- |
| AI collaboration governance | [ai-governance.md](ai-governance.md) | `R1` | [CodeNote W62 parent](../../../CzzProj/CodeNote/vibe/specs/260710/1636-codex-execution-journal-evolution/spec.md#L1) |

## Scope Boundary

- 本 Manifest 只拥有 EzCodeMark 的本地 AI 协作接纳与项目映射，不复制 CodeNote 的跨项目算法。
- 应用业务、发布、凭据/权限、DB、外部配置和 Git 操作继续受项目规则与任务级授权约束。
