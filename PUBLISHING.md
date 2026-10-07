# Publish Digital Marathon to GitHub

These owner-operated launchers publish Digital Marathon **2.1.27** to the public repository **girishxp/DigitalMarathon**. They do not publish PulseStudio or reuse its credentials. Nothing is published merely by running the app.

- Mac: **Publish Digital Marathon.command**
- Windows: **Publish Digital Marathon - Windows.bat**

Install Git and GitHub CLI, then sign in once with `gh auth login --hostname github.com` as `girishxp`. Normal users need neither tool to run Digital Marathon. Tokens remain in GitHub CLI's credential storage; none are included in the application or publishers.

## First-time owner setup

Create a **public** `girishxp/DigitalMarathon` repository on GitHub. Prepare a clean checkout on `main` at `~/Developer/DigitalMarathon` (Windows: `%USERPROFILE%\Developer\DigitalMarathon`). Its `origin` must be exactly this repository. The publisher requires local `main` to match remote `main`, and refuses an existing release or tag for the selected version, including drafts.

Keep the reviewed, sanitized **DigitalMarathon-source** snapshot beside the extracted `digital-marathon` folder. It contains source, documentation, resources and scripts only. It must match the source files included in the verified release ZIP. Keep these release assets together:

```text
DigitalMarathon-source/
digital-marathon/
digital-marathon-cross-platform-v2.1.27-click-to-launch.zip
SHA256SUMS-v2.1.27.txt
```

The exact ZIP/checksum may alternatively be placed in Downloads. The publisher never guesses the newest ZIP or publishes an unrelated product. If paths differ, use the options below or set `DIGITAL_MARATHON_REPO`, `DIGITAL_MARATHON_SOURCE`, `DIGITAL_MARATHON_ZIP` and `DIGITAL_MARATHON_CHECKSUM`.

## Review before publication

Mac example:

```bash
./"Publish Digital Marathon.command" --dry-run \
  --source "/path with spaces/DigitalMarathon-source" \
  --repo-dir "$HOME/Developer/DigitalMarathon"
```

Windows example:

```powershell
& '.\Publish Digital Marathon - Windows.bat' --dry-run `
  --source 'C:\Release Files\DigitalMarathon-source' `
  --repo-dir "$HOME\Developer\DigitalMarathon"
```

Both support `--zip PATH`, `--checksum PATH`, `--repo-dir PATH`, `--source PATH`, `--dry-run` and `--help`. Quote every path containing spaces. A dry run performs all product, version, digest, source and Git/GitHub preflight checks. It performs no commit, push, tag, release, checkout-copy, source removal or analytics send. It can use temporary files and make read-only GitHub requests. A missing repository or incomplete checkout is reported rather than created automatically.

When the dry run passes, double-click the appropriate publisher (or rerun the same command without `--dry-run`). Review the version, repository, source count, asset path and SHA-256, then answer the final publishing prompt.

## Publication sequence

1. Validate the exact ZIP filename, application version, JAR manifest, Mac product identifier and matching SHA-256 checksum.
2. Validate the sanitized snapshot, reject private/compiled/runtime/QA files and obvious credentials, and compare its packaged files with the verified ZIP.
3. Require an authenticated owner, configured Git author/committer identity, a public correct repository, exact origin, clean synchronized `main`, and an unused version/tag.
4. Copy only validated source files into the checkout and remove obsolete tracked files within that same validated scope. Commit changed source, push `main`, create/push an annotated version tag and verify that it resolves to the reviewed commit. Existing local uncommitted work is never reset or cleaned.
5. Create a **draft** release targeting the pushed commit, upload the ZIP and checksum, download both assets again and compare their hashes/bytes.
6. Publish and mark **Latest** only after both uploaded assets verify, then check GitHub's Latest endpoint.

The release contains the one combined Mac/Windows ZIP plus its checksum. GitHub separately offers automatic source archives. The source repository excludes bundled runtimes, `.app`/JAR/executable/native program binaries, QA/build output, logs, activity history, certificate downloads and credentials. Icon resources and the product PDF are retained as source/documentation assets.

Tell users to download **digital-marathon-cross-platform-v2.1.27-click-to-launch.zip** from the Release assets. GitHub's **Source code (zip/tar.gz)** archives contain the sanitized repository and lack the runtime/JAR needed for direct launch. Pushing a source commit alone does not make an app update available: publishing a newer **Release** as Latest does. Prompts appear only when the app is running.

## If something fails

A preflight failure changes neither the checkout nor GitHub. After the final publishing prompt, a failed commit/push leaves local source changes for review. A failed upload or verification leaves the GitHub release as a draft; it is never made Latest by that failed run. The scripts do not force-push, overwrite release assets, delete tags or reset local work.

Inspect any partial draft and its target commit before deciding how to recover. Rerunning refuses an existing tag or draft, so recovery is deliberate rather than overwriting evidence. Do not delete a published release/tag to reuse its version; build a newer version.

## Updates and basic analytics

Users check `girishxp/DigitalMarathon` at startup and every 15 minutes while running, or manually from **Help > Updates & Privacy**. They choose **Download Update**, **Remind Me Later** for 24 hours or **Skip This Version**. Downloads require a matching release SHA-256 checksum; installation remains a manual extract-and-relaunch step. An unpublished/private repository yields no available public update.

GitHub asset download counts provide basic distribution statistics. The release's public ingestion configuration uses the **existing PulseStudio PostHog project**, without creating a new project, changing a billing plan or adding a card. Anonymous usage remains **opt-in and off by default**.

Digital Marathon has its own [dashboard](https://us.posthog.com/project/580438/dashboard/2181978). Every outbound Digital Marathon event starts with `digital_marathon_` and retains `app_id = digital-marathon`. Save that app filter inside every Digital Marathon insight and every event series/funnel step; a dashboard-only filter is not sufficient when an insight is opened separately. The existing PulseStudio dashboard stays unchanged, and its named-event reports remain separate. Project-wide all-event reports still need an app filter. Shared-project administrators can access both apps' events; the filters separate reporting, not project permissions. Keep the owner dashboard private.

Optional events contain no input/activity totals, names, paths or history. Digital Marathon requests no GeoIP enrichment, person profiles, autocapture or session recording. Source IP still exists in ordinary network transit. See **ANALYTICS.md** for actual event fields and user controls. Configuring a dashboard does not itself verify live event receipt. Publishing does not send analytics events.
