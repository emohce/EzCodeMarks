---
id: ezcodemark-intellij-ant-instrumenter-task-race
status: verified
scope: project
fingerprint: intellij-gradle-2.16-instrumentcode-instrumenttestcode-concurrent-ant-instrumenter-nested-skip
first_seen: 2026-07-15
last_verified: 2026-07-15
review_after: 2027-01-15
evidence:
  - build.gradle.kts instrumentation ordering
  - current serialized Gradle verification
tags:
  - intellij-platform
  - gradle
  - instrumentation
  - concurrency
---

# IntelliJ Ant Instrumenter Task Race

## Symptom

Combined compilation/test runs intermittently fail in IntelliJ bytecode instrumentation with `instrumentIdeaExtensions doesn't support nested "skip"`.

## Wrong Assumption

Assuming Gradle may safely run `instrumentCode` and `instrumentTestCode` concurrently because they are separate task instances.

## Verified Root Cause

IntelliJ Platform Gradle Plugin 2.16 uses shared Ant instrumentation task definitions. Parallel main/test instrumentation can corrupt that shared setup and surface a misleading nested-element error.

Evidence: [build.gradle.kts](../../../build.gradle.kts#L89) orders test instrumentation after main instrumentation; focused and full test/build tasks pass with that ordering.

## Detection Order

1. Confirm the error originates in IntelliJ instrumentation rather than Kotlin compilation.
2. Check whether main and test instrumentation overlapped in the task graph.
3. Re-run with explicit task ordering, not repeated blind retries.
4. Verify both `test` and `buildPlugin`.

## Prevention Rule

Keep `instrumentTestCode.mustRunAfter("instrumentCode")` while using this IntelliJ Platform Gradle Plugin/toolchain combination. Do not parallelize these two tasks in custom verification wrappers.

## Latest Applicable Path

- Ordering constraint: [build.gradle.kts](../../../build.gradle.kts#L89)
- Acceptance evidence: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: failure is inside IntelliJ Ant instrumentation and the two instrumentation tasks may overlap.
- Steps:
  1. Add a one-way `mustRunAfter` relationship from test to main instrumentation.
  2. Run Gradle verification serially.
  3. Confirm both test instrumentation and plugin packaging finish.
- Verification: current `test` and `buildPlugin` executions pass.
- Applicability boundary: IntelliJ Platform Gradle Plugin 2.16 instrumentation tasks in this repository.
- Fallback: if serialization does not resolve it, inspect the plugin version's instrumenter implementation before disabling instrumentation.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-15 | Commit message helper integration | Combined main/test instrumentation | Concurrent shared Ant task setup | Explicit task ordering and serialized verification | Verified by tests and plugin build |
