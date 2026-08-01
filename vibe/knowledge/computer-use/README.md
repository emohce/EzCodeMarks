# EzCodeMark Computer Use Routes

Tool: codex

Use this index only when a task requires visible GUI evidence. Purpose-built APIs, Gradle tests and static/plugin verification remain the default for non-visual evidence.

## Verified Routes

- [IntelliJ plugin runtime smoke](jetbrains-plugin-smoke.md#L1): real installed IntelliJ app shell with isolated config/system/log/plugin paths, disposable Git project and local fake Codex runtime.

## Project Session Evidence

- [Session index and recording rules](sessions/README.md#L1): one project-local `computer-use-session/v1` record per Computer Use session.
- A new session file is created before the first Computer Use method. Every observation/action method is appended in order before the next method; session evidence exists even when the reusable route and error memory are unchanged.
- Historical reconstruction must be marked `reconstructed-partial` with explicit gaps. Only a ledger that contains every method may be `live-complete`.

## Routing Rules

- Resolve by route ID, host, application/surface and task kind before the first UI call.
- Reuse the verified route and its failure links; do not rediscover the app or replay accepted scenarios without a changed dependency or acceptance requirement.
- Update the route only after a material change passes assertions and cleanup. Record reusable failures once in [project error memory](../error-memory/README.md#L1).
- The detailed project session may retain safe exact method names, app IDs, key chords and ephemeral indices/coordinates, but not raw screenshots/AX trees, credentials, prompts, secret text, hidden reasoning, full transcripts or absolute host paths.
- Global extraction links the project session/route and copies only a verified generalized rule, route or error fingerprint.
