---
id: ezcodemark-intellij-patched-coroutines-runtime-shadowing
status: verified
scope: project
fingerprint: intellij-2025.3-tests-nosuchmethod-limitedparallelism-external-coroutines-shadow-patched-runtime
first_seen: 2026-07-15
last_verified: 2026-07-15
review_after: 2027-01-15
evidence:
  - build.gradle.kts testRuntimeClasspath exclusions
  - current full Gradle test result
tags:
  - intellij-platform
  - gradle
  - coroutines
  - test-runtime
---

# IntelliJ Patched Coroutines Runtime Shadowing

## Symptom

IntelliJ fixture tests fail before the feature assertion with a linkage error such as `NoSuchMethodError` for `limitedParallelism$default`.

## Wrong Assumption

Treating the Maven `kotlinx-coroutines-core` runtime brought by test libraries as ABI-compatible with the JetBrains-patched coroutine classes shipped inside IDEA 2025.3.

## Verified Root Cause

The test runtime classpath placed an external Kotlin/coroutines runtime ahead of IDEA's patched runtime. IntelliJ code then resolved a method signature that the shadowing artifact did not provide.

Evidence: the scoped exclusions in [build.gradle.kts](../../../build.gradle.kts#L43) restore IDEA's runtime; the current full test suite passes and is recorded in the task [verification ledger](../../specs/260715/1208-commit-message-helper-integration/verify.md).

## Detection Order

1. Read the first linkage error and identify the declaring Kotlin/coroutines class.
2. Inspect `testRuntimeClasspath` for duplicate Kotlin/coroutines artifacts.
3. Compare the external artifact with the classes shipped by the target IntelliJ SDK.
4. Re-run one IntelliJ fixture test before the full suite.

## Prevention Rule

For IntelliJ 2025.3 fixture tests, do not add or retain a Maven coroutine/stdlib runtime that shadows the SDK-patched runtime. Audit `testRuntimeClasspath` whenever a new test dependency brings Kotlin transitively.

## Latest Applicable Path

- Runtime exclusion: [build.gradle.kts](../../../build.gradle.kts#L43)
- Acceptance evidence: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: failure occurs during IntelliJ fixture startup and duplicate Kotlin/coroutines artifacts are present.
- Steps:
  1. Exclude external `kotlinx-coroutines-core` and Kotlin runtime artifacts only from `testRuntimeClasspath`.
  2. Keep the SDK-bundled runtime authoritative.
  3. Run the failing fixture test, then the full `test` task.
- Verification: the current full suite passes with the exclusions active.
- Applicability boundary: IntelliJ Platform fixture/runtime linkage; not a blanket rule for production Kotlin applications.
- Fallback: if the linkage error remains, print dependency insight and verify the exact class origin before adding exclusions.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-15 | Commit message helper integration | IntelliJ fixture startup | External Maven Kotlin/coroutines runtime on test classpath | SDK runtime made authoritative through scoped exclusions | Verified by full tests |
