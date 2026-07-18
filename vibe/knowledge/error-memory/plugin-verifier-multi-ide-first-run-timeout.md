---
id: ezcodemark-plugin-verifier-multi-ide-first-run-timeout
status: verified
scope: project
fingerprint: plugin-verifier-multi-ide-first-run-outer-timeout-during-ide-preparation
first_seen: 2026-07-15
last_verified: 2026-07-16
review_after: 2027-01-15
evidence:
  - final verifyPlugin execution against three IDE targets
  - Gradle and Plugin Verifier cache/process progress
  - r4 outer-timeout recovery followed by a bounded cached rerun with three compatible verdicts
tags:
  - intellij-platform
  - plugin-verifier
  - gradle
  - timeout
---

# Plugin Verifier Multi-IDE First-Run Timeout

## Symptom

An outer 120-second or 600-second command timeout expires while `verifyPlugin` is preparing/downloading several configured IDE distributions, even though the verifier process is still progressing.

## Wrong Assumption

Treating the absence of immediate verifier verdicts as a plugin hang and repeatedly restarting the full multi-IDE task.

## Verified Root Cause

The first multi-target run includes IDE distribution preparation, dependency/cache population, and verification setup. That aggregate work can exceed a generic command timeout without indicating a plugin defect. Process/cache progress continued, and the final run completed all three targets successfully.

See the three target verdicts in the final [verification matrix](../../specs/260715/1208-commit-message-helper-integration/verify.md). The repository does not own a durable multi-target list; the lesson applies when the current verifier invocation is supplied several targets.

## Detection Order

1. Check whether the Gradle/Plugin Verifier process is still alive.
2. Inspect cache/report timestamps or download/preparation output for continued progress.
3. Distinguish an outer orchestration timeout from a verifier compatibility verdict.
4. Allow a bounded first-run window sized for every target in the current invocation.
5. Reuse the populated cache for subsequent verification.

## Prevention Rule

Budget the first multi-IDE Plugin Verifier run separately from cached runs. Do not classify an outer timeout as incompatibility while the verifier is demonstrably progressing.

## Latest Applicable Path

- Acceptance evidence: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: several IDE targets were supplied to the current verifier invocation and the first run times out without a failure verdict.
- Steps:
  1. Confirm live process/cache progress.
  2. Rerun once with a bounded first-run allowance up to 30 minutes.
  3. Preserve the Gradle/IDE cache and inspect each generated verdict file.
  4. Treat only verifier verdicts or a genuinely stalled process as failure evidence.
- Verification: both accepted task runs completed IU-253.33813.25, IU-261.26222.65, and IU-262.8665.176 as compatible; the r4 retry finished from the populated cache within the widened bounded allowance.
- Applicability boundary: local multi-target Plugin Verifier preparation; not a blanket reason to ignore an actually stalled or failed verifier.
- Fallback: verify one target at a time to isolate a corrupt distribution or target-specific failure.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-15 | Commit message helper integration | First three-IDE compatibility run | Generic outer timeout interpreted as verifier failure | Progress inspection, longer bounded run, cache reuse | All three IDE targets compatible |
| 2 | 2026-07-16 | Commit message helper integration r4 | A 120-second outer limit ended after two compatible verdicts while the third target was still progressing | Treating the outer timeout as a third-target failure or restarting repeatedly | Recalled the verified route, preserved caches, and ran one bounded continuation | All three targets compatible; no plugin incompatibility |
