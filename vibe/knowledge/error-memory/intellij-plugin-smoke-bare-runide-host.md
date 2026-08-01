---
id: ezcodemark-intellij-plugin-smoke-bare-runide-host
status: verified
scope: project
fingerprint: macos-computer-use-retries-attach-to-gradle-runide-bare-java-host-without-stable-app-accessibility-identity
first_seen: 2026-08-01
last_verified: 2026-08-01
review_after: 2026-11-01
evidence:
  - runtime-receipt
  - user-confirmed
tags:
  - intellij-platform
  - computer-use
  - smoke-test
  - sandbox
  - macos
---

# IntelliJ Computer Use Smoke Must Use a Real App Shell

## Symptom

Computer Use could not obtain a stable actionable IntelliJ surface from the Gradle `runIde`/bare Java host, and repeated application discovery or attachment produced no new acceptance evidence.

## Wrong Assumption

Because `runIde` creates an IntelliJ development sandbox, its JVM process is automatically equivalent to a genuine macOS app-bundle identity for Computer Use.

## Verified Root Cause

Plugin isolation and GUI attachability are separate properties. In the current macOS route, the bare process did not expose a reliable application/accessibility identity, so repeating the same attachment could not validate the UI. Launching a real installed IntelliJ app bundle with `IDEA_PROPERTIES` redirected to isolated config/system/log and the actual Gradle plugin sandbox produced a stable surface and passed the smoke assertions.

## Correct Detection Order

1. Distinguish build/plugin sandbox evidence from Computer Use host identity.
2. Run `prepareSandbox` and verify the plugin directory.
3. Resolve a genuine IntelliJ app bundle before any UI call.
4. Redirect all mutable IDE paths and open a disposable Git project.
5. Observe the known app identity once; if it fails, use one distinct fallback or stop.

## Prevention Rule

For Codex-driven macOS UI smoke, follow the verified [IntelliJ plugin route](../computer-use/jetbrains-plugin-smoke.md#L1). Do not treat repeated attachment to the same bare host as progress. `runIde` remains available for developer/manual use and is not globally forbidden.

## Alternative Route

- Status: `verified`
- Preconditions: prepared plugin sandbox, installed IntelliJ app bundle, disposable project and isolated run root.
- Ordered steps: prepare sandbox -> render absolute isolated `idea.properties` -> launch real app shell as a new instance -> observe known identity -> run bounded assertions -> quit normally -> verify process cleanup.
- Verification: IntelliJ 2026.1.4 accepted Settings transactions/navigation, true multi-turn Chat, permission/approval/Stop/New conversation, native Commit Replace and disposer cleanup with a local fake runtime.
- Applicability boundary: macOS Computer Use acceptance for this JetBrains plugin. It does not replace Gradle tests, Plugin Verifier or human `runIde` workflows.
- Fallback: if the real app shell or sandbox identity cannot be proven, stop and report runtime UI evidence unavailable.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Evidence | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-08-01 | Environment Actions / Codex Chat runtime lane | UI smoke after Mac unlock | repeat bare `runIde`/Java attachment | no stable actionable surface; real app-shell route passed | actual app bundle plus isolated properties/plugin sandbox | verified |
