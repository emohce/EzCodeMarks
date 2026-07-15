---
id: ezcodemark-httpurlconnection-response-code-timeout-retry-oom
status: verified
scope: project
fingerprint: httpurlconnection-responsecode-read-timeout-retry-same-connection-runaway-allocation-oom
first_seen: 2026-07-15
last_verified: 2026-07-15
review_after: 2027-01-15
evidence:
  - LlmProviderClient cancellable response-header wait
  - HttpLlmProviderClient timeout and delayed-header tests
tags:
  - httpurlconnection
  - cancellation
  - timeout
  - oom
---

# `HttpURLConnection.responseCode` Timeout Retry OOM

## Symptom

A delayed provider response causes runaway allocation and eventually an OOM/heap dump when `responseCode` is repeatedly read on the same timed-out `HttpURLConnection`.

## Wrong Assumption

Treating a `SocketTimeoutException` from `responseCode` as a harmless polling signal and invoking `responseCode` again on the same connection.

## Verified Root Cause

`HttpURLConnection.responseCode` is a blocking state transition, not a cancellation poll API. Re-entering it after a read timeout on the same connection can repeatedly rebuild internal response/error state and allocate without progress.

The fixed route performs exactly one blocking `responseCode` call on an executor future, polls that future through the IntelliJ `ProgressIndicator`, and disconnects/cancels on deadline or user cancellation. See [LlmProviderClient.kt](../../../src/main/kotlin/emohce/data/commitmessage/LlmProviderClient.kt#L258) and its [HTTP regression tests](../../../src/test/kotlin/emohce/data/commitmessage/HttpLlmProviderClientTest.kt#L1).

## Detection Order

1. Distinguish response-header delay from response-body delay.
2. Confirm whether `responseCode` is invoked more than once for one connection.
3. Inspect heap growth and thread state without opening or persisting sensitive heap contents.
4. Replace repeated connection calls with one future and bounded indicator polling.
5. Test delayed headers, cancellation, timeout, and split UTF-8 bodies.

## Prevention Rule

Never poll cancellation by repeatedly calling a blocking `HttpURLConnection` state method. Execute it once, poll the future/deadline, and disconnect on cancellation.

## Latest Applicable Path

- Cancellable header wait: [LlmProviderClient.kt](../../../src/main/kotlin/emohce/data/commitmessage/LlmProviderClient.kt#L258)
- Regression coverage: [HttpLlmProviderClientTest.kt](../../../src/test/kotlin/emohce/data/commitmessage/HttpLlmProviderClientTest.kt#L1)
- Acceptance evidence: [verify.md](../../specs/260715/1208-commit-message-helper-integration/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: provider transport uses `HttpURLConnection`, needs IDE proxy support, and must remain cancellable during response headers.
- Steps:
  1. Submit one `responseCode` invocation to the application executor.
  2. Poll only the future with a short bounded interval while checking the `ProgressIndicator` and overall deadline.
  3. Disconnect and cancel the future on cancellation/deadline.
  4. After headers, read UTF-8 through `InputStreamReader` with short socket polling under the same deadline.
- Verification: delayed-header, cancellation, timeout/fallback, and split multibyte response tests pass in the full suite.
- Applicability boundary: cancellable `HttpURLConnection` transport; clients with native async cancellation should use their own API.
- Fallback: if executor cancellation cannot stop the platform connection, disconnect first and treat the worker as bounded cleanup rather than re-entering `responseCode`.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-15 | Commit message helper integration | Delayed response-header regression test | Repeated `responseCode` after socket timeout | Single future call plus indicator/deadline polling | Verified by focused and full HTTP tests |
