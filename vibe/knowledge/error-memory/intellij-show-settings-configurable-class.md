---
id: ezcodemark-intellij-show-settings-configurable-class
status: verified
scope: project
fingerprint: intellij-showsettingsutil-string-overload-configurable-id-opens-last-page
first_seen: 2026-08-01
last_verified: 2026-08-01
review_after: 2027-02-01
evidence:
  - Environment ToolWindow opened the previously selected Codex page
  - class-overload source regression
  - direct Environment and Codex Settings runtime checks
tags:
  - intellij-platform
  - settings
  - configurable
  - navigation
  - toolwindow
---

# Open IntelliJ Settings by Configurable Class, Not Descriptor ID String

## Symptom

Clicking `Edit environments…` opens Settings but leaves the previously selected `Codex CLI` page active instead of selecting `Environment Actions`.

## Wrong Assumption

Treating the `String` argument of `ShowSettingsUtil.showSettingsDialog(Project, String)` as a Configurable descriptor ID.

## Verified Root Cause

The string overload selects by Configurable display name, so passing `emohce.settings.environmentActions` does not resolve the intended page and Settings falls back to prior selection. The ToolWindow now calls the typed class overload for both [Environment Actions and Codex CLI](../../../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L104-L124), with a source-level regression that rejects the ID-string form ([test](../../../src/test/kotlin/emohce/presentation/environmentaction/EnvironmentActionsDescriptorTest.kt#L98-L107)).

## Detection Order

1. Open a different Settings page first to make fallback visible.
2. Invoke each plugin-owned Settings button from its actual ToolWindow surface.
3. Verify the breadcrumb and page-specific controls, not only that a Settings window opened.
4. Inspect the selected `ShowSettingsUtil` overload.
5. Prefer a Configurable class or an explicit Configurable predicate when stable ID matching is required.

## Prevention Rule

Do not pass a descriptor ID to the display-name string overload of `ShowSettingsUtil`. Use the Configurable class overload for a concrete page, and cover the call shape plus a real breadcrumb/page check.

## Latest Applicable Path

- Typed settings navigation: [EnvironmentActionsPanel.kt](../../../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L104-L124)
- Regression guard: [EnvironmentActionsDescriptorTest.kt](../../../src/test/kotlin/emohce/presentation/environmentaction/EnvironmentActionsDescriptorTest.kt#L98-L107)
- Runtime acceptance: [verify.md](../../specs/260731/1720-environment-actions-codex-chat/verify.md)

## Alternative Route

- Status: `verified`
- Preconditions: plugin UI needs to open one registered IntelliJ Configurable.
- Steps:
  1. Reference the runtime Configurable class directly.
  2. Reopen Settings after a different page was last selected.
  3. Assert breadcrumb and page-specific controls.
  4. Retain a regression that rejects descriptor-ID strings in the call site.
- Verification: both ToolWindow buttons opened their correct pages in IntelliJ 2026.1.4; the full suite and verifier matrix pass.
- Applicability boundary: `ShowSettingsUtil` navigation to registered Configurable pages.
- Fallback: use the predicate overload against `ConfigurableWithId.id` when class selection cannot represent a dynamic target.

## Occurrence History

| Occurrence | Date | Task | Trigger | Failed Route | Recovery | Outcome |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2026-08-01 | Environment Actions / Codex Chat runtime acceptance | ToolWindow settings button after visiting Codex CLI | descriptor ID passed to display-name overload | Configurable class overload and regression | each button selects the intended Settings page |
