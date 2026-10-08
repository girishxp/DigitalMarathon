# Publish Digital Marathon to GitHub

These owner-operated launchers publish Digital Marathon **2.1.29** to the public repository **girishxp/DigitalMarathon**. The PulseStudio publisher belongs to a different application; use these Digital Marathon publishers for this repository. Nothing is published merely by running the app.

- Mac: **Publish Digital Marathon.command**
- Windows: **Publish Digital Marathon - Windows.bat**

The Mac `.command` file does not run on Windows. The two platform-specific launchers publish the same combined release to the same repository; either host can publish it. **Publish PulseStudio.command** publishes PulseStudio and is not the publisher for this app.

Install Git and GitHub CLI, then sign in once with `gh auth login --hostname github.com` as `girishxp`. Normal users need neither tool to run Digital Marathon. Tokens remain in GitHub CLI's credential storage; none are included in the application or publishers.

## First-time owner setup

Create a **public** `girishxp/DigitalMarathon` repository on GitHub. Prepare a clean checkout on `main` at `~/Developer/DigitalMarathon` (Windows: `%USERPROFILE%\Developer\DigitalMarathon`). Its `origin` must be exactly this repository. The publisher requires local `main` to match remote `main`, and refuses an existing release or tag for the selected version, including drafts.

Keep the **original combined ZIP** beside the extracted `digital-marathon` folder, or select it with `--zip PATH`. The publisher can also look for the exact expected ZIP in Downloads. This is the one complete package: no separate source snapshot or checksum download is required.

```text
digital-marathon-cross-platform-v2.1.29-click-to-launch.zip
digital-marathon/
```

The publisher derives a temporary sanitized source snapshot from the ZIP's allowlisted source, resources, documentation and scripts, including the packaged `.gitignore` and `.gitattributes`. It computes SHA-256 directly from the original ZIP and creates a temporary checksum file for the release. If you explicitly supply a checksum file, it must match; an invalid supplied checksum is rejected rather than ignored.

If paths differ, use `--zip` and `--repo-dir` or set `DIGITAL_MARATHON_ZIP` and `DIGITAL_MARATHON_REPO`. Optional `--source`/`DIGITAL_MARATHON_SOURCE` and `--checksum`/`DIGITAL_MARATHON_CHECKSUM` inputs are available for reviewed external files. A supplied source snapshot must match the approved ZIP source, and a supplied checksum must pass strict verification. The publisher never guesses the newest ZIP or publishes an unrelated product.

## One complete combined package

The release asset must be **digital-marathon-cross-platform-v2.1.29-click-to-launch.zip**, with a single **digital-marathon/** root. Inside it, include **Digital Marathon.app**, **Start Digital Marathon - Windows.bat**, the complete **app-files/** application and Windows runtime, and the current documentation. The Mac app belongs inside this same combined folder and ZIP; do not replace it with a separate Mac download or a source-only archive.

Local development and testing require no Apple Developer account or notarization. Packaging must verify the Mac bundle's local ad-hoc signature and test launch from an extracted package. A browser-downloaded unnotarized copy can still be blocked by macOS; local launch checks do not guarantee prompt-free use on every Mac or download. Developer ID signing and notarization are an optional later step for public distribution, not a requirement for this local development build. None of these packaging checks require removing quarantine attributes or resetting macOS security settings.

## Review before publication

Mac example:

```bash
./"Publish Digital Marathon.command" --dry-run \
  --zip "/path with spaces/digital-marathon-cross-platform-v2.1.29-click-to-launch.zip" \
  --repo-dir "$HOME/Developer/DigitalMarathon"
```

Windows example:

```powershell
& '.\Publish Digital Marathon - Windows.bat' --dry-run `
  --zip 'C:\Release Files\digital-marathon-cross-platform-v2.1.29-click-to-launch.zip' `
  --repo-dir "$HOME\Developer\DigitalMarathon"
```

Both support `--zip PATH`, `--repo-dir PATH`, `--dry-run` and `--help`, plus optional `--checksum PATH` and `--source PATH` overrides. The original combined ZIP is sufficient; these overrides are not required. Quote every path containing spaces. A dry run performs all product, version, digest, source and Git/GitHub preflight checks. It performs no commit, push, tag, release, checkout-copy, source removal or analytics send. It can use temporary files and make read-only GitHub requests. A missing repository or incomplete checkout is reported rather than created automatically.

When the dry run passes, double-click the appropriate publisher (or rerun the same command without `--dry-run`). Review the version, repository, source count, asset path and SHA-256, then answer the final publishing prompt.

## Publication sequence

1. Validate the exact original ZIP filename, application version, JAR manifest and Mac product identifier. Compute the ZIP SHA-256; if an external checksum is supplied, require an exact match. Prepare a temporary checksum when no external file is supplied.
2. Derive a temporary sanitized source snapshot from allowlisted ZIP entries, including `.gitignore` and `.gitattributes`. Reject private/compiled/runtime/QA files and obvious credentials. An optional external source snapshot must match the verified ZIP source.
3. Require an authenticated owner, configured Git author/committer identity, a public correct repository, exact origin, clean synchronized `main`, and an unused version/tag.
4. Copy only validated source files into the checkout and remove obsolete tracked files within that same validated scope. Commit changed source, push `main`, create/push an annotated version tag and verify that it resolves to the reviewed commit. Existing local uncommitted work is never reset or cleaned.
5. Create a **draft** release targeting the pushed commit, upload the ZIP and checksum, download both assets again and compare their hashes/bytes.
6. Publish and mark **Latest** only after both uploaded assets verify, then check GitHub's Latest endpoint.

The release contains the one combined Mac/Windows ZIP plus its checksum, which the publisher can generate. Users need only the combined ZIP to run the app; owners need no second source/checksum download to publish it. GitHub separately offers automatic source archives. The source repository excludes bundled runtimes, `.app`/JAR/executable/native program binaries, QA/build output, logs, activity history, certificate downloads and credentials. Icon resources and the product PDF are retained as source/documentation assets.

Tell users to download **digital-marathon-cross-platform-v2.1.29-click-to-launch.zip** from the Release assets. GitHub's **Source code (zip/tar.gz)** archives contain the sanitized repository and lack the runtime/JAR needed for direct launch. Pushing a source commit alone does not make an app update available: publishing a newer **Release** as Latest does. Prompts appear only when the app is running.

## If something fails

A preflight failure changes neither the checkout nor GitHub. After the final publishing prompt, a failed commit/push leaves local source changes for review. A failed upload or verification leaves the GitHub release as a draft; it is never made Latest by that failed run. The scripts do not force-push, overwrite release assets, delete tags or reset local work.

Inspect any partial draft and its target commit before deciding how to recover. Rerunning refuses an existing tag or draft, so recovery is deliberate rather than overwriting evidence. Do not delete a published release/tag to reuse its version; build a newer version.

## Updates and basic analytics

Publishing a newer version as **Latest** using either Digital Marathon publisher makes it available to existing Digital Marathon **2.1.27 or later** installations that have automatic checks enabled. Apps check `girishxp/DigitalMarathon` at startup and every 15 minutes while running; a user can check manually from **Help > About > Check for updates**. Pushing source commits alone does not trigger update notifications. The app does not notify while it is closed. Version 2.1.29 restores automatic checks if an older preference had disabled them; no update-check switch is shown. Version 2.1.26 and older do not contain this updater. An unpublished/private repository yields no available public update.

Users choose **Download Update**, **Remind Me Later** for 24 hours or **Skip This Version**. Downloading and SHA-256 verification run in the background while the current session and tracking continue. Users may install later. They may extract the verified ZIP into a new folder while continuing the current session. To run the new version, they quit the current app and launch the new `.app` or `.bat`. A restart is required to activate new Java/native components; this package does not hot-swap or silently overwrite its running application. Activity history remains outside the launch folder.

GitHub asset-download counts provide distribution statistics. Basic usage analytics is automatically active at launch with this release's public ingestion configuration. There is no first-run choice or user-facing analytics toggle. It uses the **existing PulseStudio PostHog project**, without creating a new project, changing a billing plan or adding a card.

Digital Marathon has its own [dashboard](https://us.posthog.com/project/580438/dashboard/2181978). Every outbound Digital Marathon event starts with `digital_marathon_` and retains `app_id = digital-marathon`. Save that app filter inside every Digital Marathon insight and every event series/funnel step; a dashboard-only filter is not sufficient when an insight is opened separately. The existing PulseStudio dashboard stays unchanged, and its named-event reports remain separate. Project-wide all-event reports still need an app filter. Shared-project administrators can access both apps' events; filters separate reporting, not project permissions. Keep the owner dashboard private.

Usage events contain no input/activity totals, typed text, certificate names, file paths or history. Digital Marathon requests no GeoIP enrichment, person profiles, autocapture or session recording. Source IP still exists in ordinary network transit. See **ANALYTICS.md** for actual event fields and reporting limits. Configuring a dashboard does not itself verify live event receipt. Publishing does not send analytics events.
