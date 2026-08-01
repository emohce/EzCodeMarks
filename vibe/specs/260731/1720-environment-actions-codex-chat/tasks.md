# Environment Actions / Codex Chat Task Ledger

## Work Units

| Work Unit | Work-order Version | Attempt | Surface | Runtime ID | State | Last Evidence | Blocker | Next Action |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WU-1 App Server delta | 1 | 1 | native-thread | `/root/appserver_delta` | accepted | Policy-free JSONL transport can be shared while strict Commit Provider and interactive Chat retain separate caller-owned policies | none | closed |
| WU-2 IntelliJ integration delta | 1 | 1 | native-thread | `/root/intellij_delta` | accepted | Public `CheckinProject`/commit-message provider, workspace non-roaming state, CAS store and Content disposer seams identified | none | closed |
| WU-3 implementation and closeout | 1 | 1 | main | runtime_id_unavailable | accepted | 29 suites / 309 tests, project/configuration/structure/XML, three IDE targets, fake-runtime UI and document/diff gates pass | none | closed |
| WU-4 Computer Use route canary | 1 | 1 | main | runtime_id_unavailable | accepted | real IntelliJ app-shell sandbox route and four deduplicated failure/memory links reconstructed from accepted runtime evidence; current ignored sandbox preconditions still exist | none | reuse route; no GUI rerun for docs-only changes |
| WU-5 Computer Use project session ledger | 1 | 1 | main | runtime_id_unavailable | accepted | `computer-use-session/v1` project index added; prior smoke reconstructed with explicit gaps and future live-complete per-method gate | none | create ledger before every future Computer Use method |

## Execution Journal

- `2026-07-31T17:20+08:00` — `WU-3/1`, App Root/main, `pending -> running`: user requested full implementation of the accepted plan. Root established new Controlled authority and retained repository write ownership.
- `2026-07-31T17:22+08:00` — `WU-1/1` and `WU-2/1`, App Root/native-thread, `pending -> running`: two bounded read-only explorers received non-overlapping App Server and IntelliJ integration questions.
- `2026-07-31` — `WU-1/1`, `running -> reported -> accepted`: Root reconciled a shared bounded App Server transport with separate strict/interactive policy owners. No explorer write was accepted because none was made; `doc_drift` identified and routed to this task.
- `2026-07-31` — `WU-2/1`, `running -> reported -> accepted`: Root reconciled native Commit APIs, project/common persistence, settings transaction and ToolWindow disposer requirements. `doc_drift` identified and routed to this task.
- `2026-07-31` — `WU-3/1`, `running -> verifying`: schema v2, project selection, CAS/merge, native Prepare Commit, platform process execution, true App Server Chat, native Codex Configurable, localization, descriptor integration and regressions were implemented. Strict Commit Provider compatibility remained in the same current-tree gate.
- `2026-07-31` — `WU-3/1`, verification checkpoint: 5 focused suites / 106 tests passed before the final edge-case additions. The forced complete rerun then passed 29 suites / 306 tests with zero failure, error or skip; `verifyPluginProjectConfiguration`, `verifyPluginStructure` and XML parsing passed.
- `2026-07-31` — `WU-3/1`, `failure_key=legacy-random-revision-cas-loop`: final source review proved that a missing v1 revision changed on each read and made CAS unwinnable. A deterministic content-bound migration revision plus a real upgrade regression closed the defect.
- `2026-07-31` — `WU-3/1`, `failure_key=process-tree-snapshot-before-stdin-close`: the first real EOF-parent regression proved that enumerating descendants after writer close loses reparented children. Descendants are now captured before EOF; the focused regression and full suite pass.
- `2026-07-31` — `WU-3/1`, `verifying -> accepted-automated`: Plugin Verifier 1.409 reported Compatible for IU-253.33813.55, IU-261.26222.65 and IU-262.9437.65. Deprecated/experimental notices remain in pre-existing Bookmark/ToolWindow surfaces; no internal API or new Environment/Chat/Commit compatibility failure was reported.
- `2026-07-31` — runtime UI lane: Computer Use could not attach because the Mac session was locked. The fake-CLI `runIde` walk-through remains unavailable and no real account/model/Commit operation was substituted.
- `2026-07-31` — documentation closeout: requirement/current/process/technical/error-memory layers were synchronized; code-link audit, XML and diff checks pass. DB memory, global rule/template and developer-soul propagation are not applicable.
- `2026-08-01` — runtime UI lane resumed after unlock in an isolated IntelliJ 2026.1.4 host with a local fake CLI/App Server. Same-thread multi-turn, permission/Skills/plugins rendering, inline approval denial, interrupt, New conversation, settings Apply/Cancel, typed settings navigation and disposer process closure passed without real credentials, inference or external writes.
- `2026-08-01` — four runtime-only defects were fixed: the project Configurable now exposes an exact Java `(Project)` constructor; approval is inline and non-blocking; Settings navigation uses Configurable classes; native Commit lookup combines focused DataContext with visible non-modal editor discovery. Focused regressions and the final 309-test suite pass.
- `2026-08-01` — native Commit host acceptance displayed Replace / Append / Cancel and changed only the Commit message after explicit Replace. No file selection, staging, Commit action or raw Git was invoked. Plugin Verifier 1.409 again reported all three configured IDE targets Compatible with no internal API.
- `2026-08-01` — `WU-3/1`, `accepted-automated -> accepted`: fake-runtime evidence closed the last lane; task/current/technical/error-memory authorities were resynchronized.
- `2026-08-01` — `WU-4/1`, global Computer Use governance canary: the accepted smoke was normalized into one project route (`real app shell + isolated config/system/log + prepareSandbox plugins + disposable project + fake CLI`). Bare-host retries, unobservable Swing popup retries, equivalent state calls and malformed key chords now have separate deduplicated error fingerprints. The current fixture/app/sandbox preconditions were read back without starting the IDE or repeating Computer Use.
- `2026-08-01` — `WU-5/1`, user corrected the route-only memory granularity: every Computer Use method must remain in a detailed sanitized project session, while global owners receive only generalized rules/routes/errors. The historical smoke is `reconstructed-partial` because exact raw calls are unavailable; future sessions cannot claim `live-complete` with any gap.

## Scope Reconciliation

- Repository writes: App Root only.
- Accepted read-only evidence: App Server transport/policy split; IntelliJ VCS/settings/state/lifecycle seams.
- Preserved unrelated dirty changes: commit project-shared unsupported-schema fix, AI-governance updates and their tests/memory remain outside this task's write ownership.
- High-risk gates: no real login/logout, model inference, Git commit, publish/deploy, production, DB/SQL or external-service write.
- Memory impact: six implementation/runtime-specific prevention records remain; this compatible governance supplement adds two project Computer Use failures, one verified project route, one project session index/partial predecessor ledger and three Computer Use-specific cross-project failure records under the CodeNote global parent. No application code or runtime acceptance claim changed.
