---
id: ezcodemark-plugin-verifier-gradle-offline-metadata-fallback
status: verified
scope: project
fingerprint: gradle-verifyplugin-cannot-resolve-dynamic-ide-metadata-offline-despite-extracted-exact-ide-caches
first_seen: 2026-07-17
last_verified: 2026-07-18
review_after: 2027-01-17
evidence:
  - Gradle verifyPlugin no-route-to-host dependency failure
  - Gradle offline no-cached-version failure
  - Plugin Verifier CLI 1.409 exact three-IDE offline matrix
  - recovered Gradle verifyPlugin three-IDE matrix
tags:
  - intellij-platform
  - plugin-verifier
  - gradle
  - offline
---

# Plugin Verifier Gradle Offline Metadata Fallback

## Symptom

`verifyPlugin` failed while resolving the configured 2026.2 IDE with `No route to host`. Retrying Gradle offline then failed with `No cached version available for offline mode`, even though exact extracted IDE distributions and the verifier CLI were already present locally.

## Wrong Assumption

Assuming Gradle offline mode can always reconstruct a dynamic Plugin Verifier target from transformed/extracted IDE caches.

## Verified Root Cause

The Gradle task still needs dependency metadata to resolve its configured IDE notation. Existing transformed IDE directories do not guarantee that the matching module metadata is available to Gradle's offline resolver. The JetBrains verifier itself can accept those exact local IDE roots directly.

This differs from the [first-run timeout route](plugin-verifier-multi-ide-first-run-timeout.md): the process was not slowly preparing targets; dependency resolution failed before verification.

On 2026-07-18 the metadata route recovered and the canonical Gradle task passed IU-253.33813.25, IU-261.26222.65, and IU-262.8665.258. This clears the active environment blocker without invalidating the original failure mode or its exact-cache fallback.

## Detection Order

1. Distinguish dependency-resolution errors from verifier compatibility verdicts and outer timeouts.
2. Retry Gradle offline once to determine whether module metadata is cached.
3. Confirm an exact verifier CLI JAR and exact extracted IDE roots by reading each `product-info.json` build number.
4. Build a fresh plugin ZIP before invoking the verifier directly.
5. Run the verifier offline into a new report directory and inspect every target's verdict file.

## Prevention Rule

Do not report a Gradle dependency-resolution failure as plugin incompatibility. Use the direct offline verifier only when the exact verifier and exact configured IDE distributions are already locally available.

## Latest Applicable Path

- Final verifier routes and recovered status: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: the current plugin ZIP, Plugin Verifier CLI, and every exact target IDE root are available locally and their product build numbers have been checked.
- Steps:
  1. Run package, project-configuration, and structure validation first.
  2. Invoke the cached Plugin Verifier CLI with `check-plugin -offline`, the plugin ZIP, and exact local IDE roots.
  3. Write to a new report directory rather than replacing prior evidence.
  4. Inspect each `verification-verdict.txt` and internal-API report.
- Verification: CLI 1.409 reported the final 259-test r7 artifact compatible with IU-253.33813.25, IU-261.26222.65, and IU-262.8665.176, with no internal-API report. The canonical Gradle route reported the same rebuilt artifact compatible with IU-253.33813.25, IU-261.26222.65, and IU-262.8665.258.
- Applicability boundary: exact local distributions only; a stale, partial, differently numbered, or missing IDE cache does not satisfy this route.
- Fallback: restore dependency metadata access or configure an explicit local IDE path, then rerun the Gradle gate.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17 | Commit message helper r7 | Network unavailable during Gradle target resolution | Online retry followed by Gradle offline resolution | Exact cached IDE roots passed directly to verifier CLI 1.409 | All three targets compatible; Gradle limitation retained |

## Recovery Status

- Active blocker: none as of 2026-07-18.
- Retained value: use this fallback only when a future Gradle metadata-resolution failure matches the fingerprint and every exact local verifier prerequisite is satisfied.
