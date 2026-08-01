---
id: ezcodemark-intellij-plugin-smoke
status: verified
scope: project
route_key: ezcodemark-macos-intellij-plugin-runtime-smoke
application: IntelliJ IDEA app bundle
surface: plugin-runtime-smoke
task_kind: jetbrains-plugin-ui-acceptance
last_verified: 2026-08-01
review_after: 2026-11-01
evidence:
  - runtime
  - user-confirmed
  - local-fixture
---

# IntelliJ Plugin Runtime Smoke Route

## Route Contract

The smoke target is the built plugin running inside a genuine IntelliJ app bundle while every mutable IDE directory is redirected to an isolated, ignored sandbox. A Gradle-launched bare JVM window is not accepted as Computer Use evidence when it cannot provide a stable application/accessibility identity. `runIde` remains a valid developer/manual route; this document owns the Codex Computer Use smoke route only.

This route follows the global [bounded Computer Use interaction rule](../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/security/rules.md#machine-local-computer-use-profiles) and [route-memory contract](../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/memory/rules.md#12-computer-use-route-memory).

## Preconditions

1. Build the actual Gradle plugin sandbox with `./gradlew prepareSandbox`; the configured IntelliJ platform and plugin layout remain defined in [build.gradle.kts](../../../build.gradle.kts#L23).
2. Resolve an installed IntelliJ IDEA app bundle and verify it exists before launch. The bundle path is machine-local evidence and is not persisted here.
3. Create an ignored run root under `build/manual-fixtures/<run>/`, an ignored disposable Git project under `build/tmp/ui-smoke`, and a local fake Codex CLI/App Server fixture. Do not use a real account, model, source transmission or Commit side effect.
4. Confirm no prior smoke IDE/fake CLI process is active and preserve the user's normal IntelliJ config, system, plugins and common data.

## Real Isolated Sandbox

Render an `idea.properties` file with absolute runtime values for the following keys; placeholders below are descriptive and are not expected to expand inside the properties file:

```properties
idea.config.path=<run-root>/config
idea.system.path=<run-root>/system
idea.plugins.path=<project-root>/.intellijPlatform/sandbox/EzCodeMarks/IU-2025.3/plugins
idea.log.path=<run-root>/log
idea.trust.all.projects=true
ide.no.platform.update=true
```

Launch the installed app shell as a new instance and inject only the isolated properties file:

```bash
IDE_APP="<resolved IntelliJ IDEA.app>"
SMOKE_ROOT="$PWD/build/manual-fixtures/<run>/idea-261"
open -n -F --env IDEA_PROPERTIES="$SMOKE_ROOT/idea.properties" -a "$IDE_APP" --args "$PWD/build/tmp/ui-smoke"
```

The acceptance identity is therefore `real IntelliJ app shell + isolated config/system/log + actual prepareSandbox plugin directory + disposable project`, not the launch command alone.

## Computer Use Sequence

1. Target the already resolved app bundle/bundle identifier directly. Do not call application discovery unless that identity fails once and is genuinely unresolved.
2. Fetch one fresh application state. Use the default accessibility diff after subsequent actions; request a full tree only when earlier context was lost, and inspect a screenshot only when accessibility evidence is incomplete.
3. Perform one meaningful element-index action or one explicit key chord using the installed Computer Use Skill's exact schema, then fetch fresh state before deciding again. Never reuse an index after page, popup, scroll, window or project changes.
4. For a known failure fingerprint, abandon the failed path and use at most one distinct verified fallback. Do not repeat equivalent attachment, full-tree, screenshot or popup calls.
5. Quit normally with the exact key-chord schema, handle the native Exit confirmation once, then verify the IDE and fake CLI process trees are gone.

## Acceptance Assertions

- The loaded plugin comes from the prepared sandbox, while normal user settings/plugins are untouched.
- Settings Apply/Reset/Cancel and typed Configurable navigation operate in the isolated host.
- One App Server process owns one ephemeral thread with multiple turns; permissions, Skills/plugins, approval Deny, Stop interrupt and New conversation behave as recorded in [runtime verification](../../specs/260731/1720-environment-actions-codex-chat/verify.md#local-runtime-host-results).
- Prepare Commit changes only the visible native Commit message after explicit Replace; it never selects, stages or commits files.
- Project/app disposal closes the App Server and child processes.

## Cleanup

- Quit through the native application flow; do not kill the app before testing disposer behavior unless the scenario explicitly tests abnormal termination.
- Verify no smoke IntelliJ or fake CLI process remains.
- Remove the exact test-created Environment from live JetBrains common data or move it into the ignored run fixture for recovery. Never edit unrelated user Environments.
- Keep ignored fixtures only when they are needed to reproduce the verified route; they are evidence, not project authority.

## Known Failure Routes

- [Bare `runIde` host is not stable Computer Use evidence](../error-memory/intellij-plugin-smoke-bare-runide-host.md#L1).
- [Swing popup can collapse the accessibility bridge](../error-memory/intellij-swing-popup-accessibility-bridge-loss.md#L1).
- [Equivalent observations/retries without state change](../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/memory/error-archive/2026-08-01-computer-use-equivalent-retry-without-state-change.md#L1).
- [Key chords must match the exact Computer Use schema](../../../../CzzProj/CodeNote/AiRef/VibePractice/Vibe_Rules/memory/error-archive/2026-08-01-computer-use-key-chord-parameter-shape.md#L1).

## Session Evidence

- Every future invocation creates and updates a project-local [`computer-use-session/v1` ledger](sessions/README.md#L1) before the first UI method. The ledger records every observation/action method even when this reusable route remains unchanged.
- The accepted 2026-08-01 runtime lane has a [reconstructed partial session](sessions/2026-08-01-intellij-environment-actions-smoke.md#L1). Its missing raw call boundaries are explicit and it cannot be used as proof of live-complete recording.
- Global extraction links the route/session evidence and retains only generalized rules or error fingerprints; it never copies the event table.

## Revalidation Triggers

Re-run only when the IntelliJ app major version, Gradle sandbox layout, plugin descriptor/runtime dependency, Computer Use capability, fake protocol, acceptance scenario or sandbox/cleanup contract changes. Documentation-only work and unchanged accepted behavior reuse this route without another GUI session.
