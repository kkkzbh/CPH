---
name: cph-release-publish
description: "Use when publishing `/home/kkkzbh/code/clion-plugin/cph` releases: stable or EAP JetBrains/CLion plugin releases, browser-extension-only releases, or combined plugin plus browser releases. Trigger when the user asks to release/publish CPH, push Marketplace updates, publish browser extension versions such as `browser-v1.0.3`, bump release versions, or reminds that CPH plugin What's New/change-notes must be updated."
---

# CPH Release Publish

## Scope

Use this skill for the `CPH` repository at `/home/kkkzbh/code/clion-plugin/cph`.

The release workflow is intentionally decoupled:

- JetBrains plugin release channel: `plugin_target=verify_only|stable|eap|both`
- Browser extension release channel: `browser_target=none|release`
- Browser-only releases use tag `browser-v<browserExtensionVersion>` and must not move stable plugin tags.

Do not publish, tag, push, or trigger workflows before the relevant local checks below are complete.

## Hard Gates

For stable or EAP JetBrains plugin publishing, update `src/main/resources/META-INF/plugin.xml` `<change-notes>` before pushing the version change. Treat stale What's New text as a release blocker, not a follow-up.

For browser-only publishing, do not require `plugin.xml` change-notes and do not bump `stablePluginVersion` or `eapPluginVersion`.

Before any release dispatch:

1. Run `git status -sb` and identify all local commits/dirty files.
2. Confirm the target version files:
   - `gradle.properties`
   - `src/main/resources/META-INF/plugin.xml` for plugin releases
   - `browser-extension/cph-target-runner/manifest.json`, `popup.html`, and `INSTALL_EXTENSION.md` for browser releases
   - `.github/workflows/publish.yml`
3. Check the target release does not already exist:
   - Stable: `gh release view v<stablePluginVersion>`
   - EAP: `gh release view v<eapPluginVersion>`
   - Browser: `gh release view browser-v<browserExtensionVersion>`
4. Run `gh auth status`.

## Local Verification

Always run the workflow/static checks:

```bash
actionlint .github/workflows/publish.yml
git diff --check
```

For browser release paths, also run:

```bash
node browser-extension/cph-target-runner/submit-core.test.js
node --check browser-extension/cph-target-runner/background.js
node --check browser-extension/cph-target-runner/page-submit.js
node --check browser-extension/cph-target-runner/submit-core.js
node --check browser-extension/cph-target-runner/extension-core.js
./gradlew --stacktrace test packageBrowserExtension --no-daemon
```

For stable plugin release paths, run:

```bash
./gradlew --stacktrace test buildPlugin -PcphVariant=stable --no-daemon
```

For EAP plugin release paths, run:

```bash
./gradlew --stacktrace test buildPlugin packageAveMujicaTheme -PcphVariant=eap --no-daemon
```

Run Gradle commands sequentially. Do not run two Gradle release builds in parallel in this repo; they share `build/test-results/test` and can corrupt each other's test-result files.

## Version Rules

Stable plugin:

- Bump `stablePluginVersion` in `gradle.properties`.
- Keep it stable-looking, without `-eap`, `-alpha`, `-beta`, or `-rc`.
- Update `plugin.xml` `<change-notes>` to that exact version.
- Expected GitHub release tag: `v<stablePluginVersion>`.

EAP plugin:

- Bump `eapPluginVersion` in `gradle.properties`.
- Keep the suffix format `-eap.N`.
- Update `plugin.xml` `<change-notes>` for the EAP-visible changes.
- Expected GitHub prerelease tag: `v<eapPluginVersion>`.

Browser extension:

- Bump `browserExtensionVersion` in `gradle.properties`.
- Keep `browser-extension/cph-target-runner/manifest.json` `"version"` aligned.
- Keep `browser-extension/cph-target-runner/popup.html` footer aligned.
- Keep `INSTALL_EXTENSION.md` build directory aligned.
- Expected GitHub release tag: `browser-v<browserExtensionVersion>`.
- Browser release is not `Latest`; the workflow uses `--latest=false`.

## Dispatch Commands

Push the final commit to `main` before workflow dispatch if the workflow or version files changed.

Browser-only:

```bash
gh workflow run "Verify and Publish" --ref main -f plugin_target=verify_only -f browser_target=release
```

Stable plugin only:

```bash
gh workflow run "Verify and Publish" --ref main -f plugin_target=stable -f browser_target=none
```

EAP plugin only:

```bash
gh workflow run "Verify and Publish" --ref main -f plugin_target=eap -f browser_target=none
```

Stable plugin plus browser:

```bash
gh workflow run "Verify and Publish" --ref main -f plugin_target=stable -f browser_target=release
```

EAP plus browser:

```bash
gh workflow run "Verify and Publish" --ref main -f plugin_target=eap -f browser_target=release
```

Stable plus EAP plus browser:

```bash
gh workflow run "Verify and Publish" --ref main -f plugin_target=both -f browser_target=release
```

## Remote Verification

After dispatch:

1. Capture the run URL printed by `gh workflow run`.
2. Watch it to completion:
   ```bash
   gh run watch <run-id> --exit-status
   ```
3. If it fails, inspect failed logs:
   ```bash
   gh run view <run-id> --log-failed
   ```
4. Verify skipped jobs match the target. Browser-only should complete `verify` and `release_browser`; `release_stable`, `release_eap`, `publish_stable`, and `publish_eap` should be skipped.
5. Verify the release:
   ```bash
   gh release view <tag> --json tagName,name,targetCommitish,publishedAt,url,assets
   ```
6. For stable/EAP plugin publishing, verify Marketplace visibility before reporting completion.

If a workflow dispatch run stays pending, check `gh run list --workflow publish.yml --limit 5`; a push-triggered verify run may be ahead of it because the workflow uses a non-canceling concurrency group. Wait rather than canceling unless the older run is genuinely stale or wrong.

## Reporting

Report:

- Release tag and URL.
- Asset names and sizes.
- Target commit.
- Workflow run URL and conclusions.
- Whether Marketplace jobs ran or were skipped.
- Final `git status -sb`.

Do not claim success from local build output alone. A release is complete only after the GitHub Actions run and GitHub Release or Marketplace state are verified.
