# Updates and basic usage analytics

Digital Marathon 2.1.28 keeps activity history on your computer. GitHub delivers releases; basic usage analytics starts automatically at launch to help the owner understand installations, versions and feature use. There is no first-run choice or user-facing analytics toggle. Manual update checks are in **Help > About > Check for updates**.

## Activity data stays local

Mouse distance, key presses, mouse clicks, active time, ranges, timestamps in activity history, certificate names and certificate contents are never sent to analytics. Neither are typed content, key identities, clipboard contents, screenshots, application/window names, file paths, exports, logs or permission records. There is no remote activity database or productivity score.

## What can leave the computer

| Connection | Information used |
|---|---|
| GitHub update check | Requests the latest release metadata for `girishxp/DigitalMarathon`; the User-Agent includes the app version, and GitHub receives ordinary network-request information. |
| GitHub package download | Downloads the selected release ZIP and its SHA-256 checksum. GitHub exposes aggregate asset-download counts to the owner. |
| PostHog basic usage | Basic product/runtime details, random installation/session IDs, launch/session timing, and limited state/feature/update/error categories listed below. |

Installation IDs are generated randomly. They are not based on a name, email, device serial number or hardware fingerprint. Feature events indicate that a control was used; they do not include input counts, activity totals, selected ranges, text or certificate names. No session recording, autocapture or user profiles are required for this integration.

The allowlisted usage fields are product name/ID (`digital-marathon`), app version, OS family and numeric release, architecture, Java numeric release, portable-runtime mode, random installation/session IDs, launch count, install age in days, event timestamp and elapsed app-session minutes. Session minutes mean how long the app is open; they are separate from the tracked Active time counter. Optional event fields are limited to Full/Mini view, appearance, distance-unit category, enabled/manual/automatic booleans, update-version strings, and predefined operation/error categories. Free-form errors, paths, ranges and activity values are rejected.

Like any network connection, the source IP exists in transit and can reach the configured provider. Requests disable PostHog GeoIP enrichment and person-profile processing; the application does not derive or submit location. The supplied configuration uses the existing PulseStudio project's public ingestion key, and reporting starts automatically when the app launches.

## Update actions

- **Automatic update checks**: checks at startup and every 15 minutes while the app is running. A manual **Check for updates** action is in **Help > About**. Version 2.1.28 restores automatic checks for older saved disabled preferences; no update-check switch is shown.
- **Download Update**: downloads a newer release in the background only after your action. Tracking and the current session continue. The package is accepted only when its SHA-256 matches the release checksum.
- **Remind Me Later**: postpones the same release prompt for 24 hours.
- **Skip This Version**: suppresses automatic prompts for that release. A manual check can still show it.

After the download is verified, you may continue using the current version and install later. Extract the downloaded package into a new folder while the current app continues. When ready to run the new version, quit the current app and open the new Mac `.app` or Windows `.bat`. Loaded Java and native input components need a restart; they are not replaced in place. Existing numerical history stays outside the launch folder. There are no update notifications while the app is closed.

## Owner configuration

The owner selected the **existing PulseStudio PostHog project** for Digital Marathon. Both apps use the same project's public ingestion token, while Digital Marathon has its own dashboard and the fixed event property `app_id = digital-marathon`. Every Digital Marathon insight must save that app filter, including every event series or funnel step. A dashboard-level filter alone does not provide reliable separation when an insight is opened independently. All Digital Marathon event names sent to PostHog also start with `digital_marathon_`, such as `digital_marathon_app_started`. This avoids collisions with PulseStudio's existing named events and keeps its dashboard unchanged. App-wide “all events” reports in the shared project still need an app filter when separate results are required.

The existing project's **public project ingestion key** is configured in `apiKey` in `app-files/resources/analytics-config.json`. To change projects later, replace this public key and the dashboard URL. This project uses US Cloud, so its ingestion host is `https://us.i.posthog.com`. Keep `provider` as `posthog`, `mode` as `automatic`, and `heartbeatMinutes` as `10`. The previous `defaultEnabled` configuration and saved analytics opt-out preference are no longer used. A project ID, personal API key or administrator token is not needed in the distributed app. Never embed those secrets or a GitHub token.

The application reads this configuration from its compiled JAR. After adding the public key, rebuild both shipped JAR copies, re-sign the Mac bundle and prepare the next release; changing only the loose source JSON does not connect an existing package. Reporting needs a valid configuration and network access. When the configured app is offline, tracking and local history continue; analytics uses a bounded temporary queue and does not make a permanent analytics log. Failed or dropped events are not a complete record of installations. Repository setup and releases are independent of analytics setup. GitHub update configuration is in `app-files/resources/update-feed.json`.

Digital Marathon does not identify people, record sessions or enable autocapture, and requests GeoIP enrichment to be disabled for its own events. Keep PulseStudio's existing project-wide settings unchanged. The app allowlists event names and properties instead of forwarding arbitrary UI content. Offline tests use a mock endpoint and generated test IDs; they do not send production analytics events.

Access to the usage dashboard follows the permissions configured in the shared PostHog project; keep the dashboard private. GitHub aggregate release download counts are available through release assets. The owner should document retention and project access before publishing an analytics-enabled release.

### Basic dashboard

The **[Digital Marathon — Basic usage dashboard](https://us.posthog.com/project/580438/dashboard/2181978)** is in the shared project. Save `app_id = digital-marathon` inside each insight and apply it to every series or funnel step. These metrics describe installations from which events were received, rather than every download or installed copy. Offline sessions, blocked requests and dropped events can reduce the counts.

| Card | Events and useful breakdown |
|---|---|
| Daily / weekly / monthly active reporting installations | Count unique `distinct_id` across `digital_marathon_app_started` and `digital_marathon_app_heartbeat`, using the desired time interval. |
| Version adoption and OS mix | Count unique `distinct_id` on `digital_marathon_app_heartbeat`, broken down by `app_version`, `platform` or `architecture`. |
| App sessions | Count unique `session_id` across `digital_marathon_app_started` and `digital_marathon_app_heartbeat`. Existing insights may also retain `digital_marathon_analytics_enabled` to include opt-in sessions from version 2.1.27. |
| Feature use | Compare `digital_marathon_view_changed` by `view`, `digital_marathon_appearance_changed` by `appearance`, and `digital_marathon_certificate_opened`, `digital_marathon_certificate_exported`, `digital_marathon_csv_exported`, `digital_marathon_help_opened` and `digital_marathon_permissions_help_opened`. |
| Export outcomes | Compare `digital_marathon_certificate_exported` with `digital_marathon_certificate_export_failed`, and `digital_marathon_csv_exported` with `digital_marathon_csv_export_failed`; failures have predefined `error_code` values. |
| Update download funnel | Follow `digital_marathon_update_available` → `digital_marathon_update_download_started` → `digital_marathon_update_downloaded`, broken down by `available_version`. Use heartbeat version adoption to see users returning on a newer release. |
| Update reliability and choices | Chart `digital_marathon_update_error` by `stage` and `error_code`, plus `digital_marathon_update_skipped` and `digital_marathon_update_postponed` by `available_version`. |

Each card above requires its own saved `app_id = digital-marathon` filter. Installation counts use random IDs; there are no user names or login profiles. Session duration, where used, is elapsed app-open time and is not the Active time metric. Closing events are best effort. Installation completion is not reported directly because updates are extracted and opened by the user. General crash reporting is not implemented; error charts cover the instrumented update and export operations. Digital Marathon disables GeoIP enrichment for its events, so a country/location card is not part of this dashboard. PulseStudio retains its existing project privacy, location reporting and dashboard layout. The shared project's administrators can access both apps' events; filtered dashboards separate reporting rather than access permissions.
