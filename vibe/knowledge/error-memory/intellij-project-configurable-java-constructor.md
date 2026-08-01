---
id: ezcodemark-intellij-project-configurable-java-constructor
status: verified
scope: project
fingerprint: intellij-project-configurable-kotlin-default-args-missing-java-project-constructor
first_seen: 2026-08-01
last_verified: 2026-08-01
review_after: 2027-02-01
evidence:
  - IntelliJ 2026.1.4 runtime constructor error
  - public Project-constructor reflection regression
  - clean Settings reopen after sandbox rebuild
tags:
  - intellij-platform
  - configurable
  - kotlin
  - dependency-injection
  - runtime
---

# IntelliJ Project Configurable Requires a Java-Visible Constructor

## Symptom

Opening Settings logs `Cannot find suitable constructor, expected (Project), (Project, CoroutineScope), (CoroutineScope), or ()` for a project Configurable even though its Kotlin primary constructor declares `Project` followed by parameters with defaults.

## Wrong Assumption

Assuming Kotlin default constructor arguments automatically expose the exact Java constructor signature that IntelliJ's Configurable instantiator searches for.

## Verified Root Cause

Kotlin's synthetic default-argument constructor is not the public `(Project)` overload required by the IntelliJ runtime. The class must expose that signature explicitly. The fixed Configurable uses [`@JvmOverloads`](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectProvidersConfigurable.kt#L56-L59), and the descriptor regression resolves the exact public [`Project` constructor](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessageDescriptorTest.kt#L105-L114).

## Detection Order

1. Inspect `idea.log` for the exact constructor contract rather than treating the Settings page as a rendering failure.
2. Reflect `getConstructor(Project::class.java)` on every project Configurable with injectable/defaulted trailing parameters.
3. Add `@JvmOverloads` or an explicit public secondary constructor.
4. Rebuild the sandbox and reopen Settings in a real IDE host.

## Prevention Rule

Every `projectConfigurable` implementation must expose one of IntelliJ's exact Java-visible constructor signatures. When Kotlin default parameters are used, lock the signature with a reflection test; source-level callability is not sufficient evidence.

## Latest Applicable Path

- Configurable constructor: [CommitProjectProvidersConfigurable.kt](../../../src/main/kotlin/emohce/presentation/commitmessage/settings/CommitProjectProvidersConfigurable.kt#L56-L59)
- Constructor regression: [CommitMessageDescriptorTest.kt](../../../src/test/kotlin/emohce/presentation/commitmessage/CommitMessageDescriptorTest.kt#L105-L114)
- Runtime acceptance: [verify.md](../../specs/260731/1720-environment-actions-codex-chat/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: an IntelliJ project Configurable has Kotlin defaulted constructor parameters or injected test seams.
- Steps:
  1. Enumerate public Java constructors with reflection.
  2. Match one exact IntelliJ-supported signature.
  3. Add `@JvmOverloads` or a public delegating constructor.
  4. Retain a descriptor/reflection regression and reopen the page in a real host.
- Verification: the reflection regression and full 309-test suite pass; IntelliJ 2026.1.4 reopens Settings without the constructor error.
- Applicability boundary: IntelliJ `applicationConfigurable`/`projectConfigurable` classes instantiated by the platform.
- Fallback: remove injectable constructor seams from the runtime class and move them behind an internal factory if an exact overload cannot be safely exposed.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-08-01 | Environment Actions / Codex Chat runtime acceptance | Open project Settings in IntelliJ 2026.1.4 | Kotlin default arguments without a Java `(Project)` constructor | `@JvmOverloads`, reflection regression and host reopen | Settings tree loads without the runtime error |
