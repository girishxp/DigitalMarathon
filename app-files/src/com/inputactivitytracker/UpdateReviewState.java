package com.inputactivitytracker;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One immutable update snapshot shared by the full review panel and compact notice.
 * The app coordinator replaces it on the EDT; it does not perform IO or touch tracking.
 */
record UpdateReviewState(Phase phase, UpdateManager.Release release, Path zip,
                         long bytes, long total, String message, IssueStage issueStage,
                         boolean noticeVisible) {
    enum Phase { NONE, CHECKING, AVAILABLE, DOWNLOADING, READY, ISSUE, INSTALLING }
    enum IssueStage { NONE, CHECK, DOWNLOAD, INSTALL }

    UpdateReviewState {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(issueStage, "issueStage");
        message = message == null ? "" : message;
        total = Math.max(0, total);
        bytes = Math.max(0, Math.min(bytes, total));
        if (zip != null) zip = zip.toAbsolutePath().normalize();
        if ((phase == Phase.AVAILABLE || phase == Phase.DOWNLOADING || phase == Phase.READY
                || phase == Phase.INSTALLING) && release == null) {
            throw new IllegalArgumentException("An update release is required for " + phase);
        }
        if ((phase == Phase.READY || phase == Phase.INSTALLING) && zip == null) {
            throw new IllegalArgumentException("A verified ZIP is required for " + phase);
        }
    }

    static UpdateReviewState initial() {
        return new UpdateReviewState(Phase.NONE, null, null, 0, 0,
                "Checks run while Digital Marathon is open.", IssueStage.NONE, false);
    }

    /** A check must not replace a transfer, verified download, or installation handoff. */
    UpdateReviewState checking() {
        if (protectedOperation()) return this;
        return new UpdateReviewState(Phase.CHECKING, release, zip, bytes, total,
                "Checking for updates…", IssueStage.NONE, noticeVisible);
    }

    UpdateReviewState checked(UpdateManager.Release found, boolean manual, String status) {
        if (protectedOperation()) return this;
        if (found == null) {
            return new UpdateReviewState(Phase.NONE, null, null, 0, 0,
                    status, IssueStage.NONE, false);
        }
        return new UpdateReviewState(Phase.AVAILABLE, found, null, 0, found.size(),
                status, IssueStage.NONE, true);
    }

    boolean canDownload() {
        return release != null && (phase == Phase.AVAILABLE
                || (phase == Phase.ISSUE && issueStage == IssueStage.DOWNLOAD));
    }

    /** Identity is unchanged when a repeated button press is not eligible. */
    UpdateReviewState beginDownload() {
        if (!canDownload()) return this;
        return new UpdateReviewState(Phase.DOWNLOADING, release, null, 0, release.size(),
                "Downloading and verifying Digital Marathon " + release.version() + "…",
                IssueStage.NONE, true);
    }

    UpdateReviewState downloading(long received, long expected) {
        if (phase != Phase.DOWNLOADING) return this;
        long boundedTotal = Math.max(0, expected);
        long boundedBytes = Math.max(bytes, Math.min(Math.max(0, received), boundedTotal));
        return new UpdateReviewState(Phase.DOWNLOADING, release, null, boundedBytes, boundedTotal,
                "Downloading and verifying Digital Marathon " + release.version() + "…",
                IssueStage.NONE, true);
    }

    UpdateReviewState downloaded(Path verifiedZip) {
        if (phase != Phase.DOWNLOADING || verifiedZip == null) return this;
        return new UpdateReviewState(Phase.READY, release, verifiedZip, total, total,
                "A verified update is ready. Keep using the app until you are ready to switch.",
                IssueStage.NONE, true);
    }

    /** Check failures cannot erase a download or its verified release and path. */
    UpdateReviewState failed(IssueStage stage, String status) {
        Objects.requireNonNull(stage, "stage");
        if (stage == IssueStage.NONE) return this;
        if (stage == IssueStage.CHECK && (protectedOperation()
                || (phase == Phase.ISSUE && issueStage == IssueStage.DOWNLOAD))) return this;
        if (stage == IssueStage.DOWNLOAD && phase != Phase.DOWNLOADING) return this;
        if (stage == IssueStage.INSTALL && phase != Phase.INSTALLING) return this;
        return new UpdateReviewState(Phase.ISSUE, release, zip, bytes, total,
                status, stage, true);
    }

    /** Hide only the offer; an ongoing transfer or verified download remains reviewable. */
    UpdateReviewState dismissNotice(String status) {
        if (protectedOperation()) return this;
        return new UpdateReviewState(phase, release, zip, bytes, total,
                status, issueStage, false);
    }

    boolean canInstall() {
        return zip != null && release != null && (phase == Phase.READY
                || (phase == Phase.ISSUE && issueStage == IssueStage.INSTALL));
    }

    /** Reserved for an installation capability; portable ZIP builds use the manual handoff. */
    UpdateReviewState beginInstallation() {
        if (!canInstall()) return this;
        return new UpdateReviewState(Phase.INSTALLING, release, zip, bytes, total,
                "Preparing the update handoff…", IssueStage.NONE, true);
    }

    UpdateReviewState installationDeferred(String explanation) {
        if (phase != Phase.INSTALLING) return this;
        return new UpdateReviewState(Phase.READY, release, zip, bytes, total,
                explanation, IssueStage.NONE, true);
    }

    boolean protectedOperation() {
        return phase == Phase.DOWNLOADING || phase == Phase.READY || phase == Phase.INSTALLING
                || (phase == Phase.ISSUE && issueStage == IssueStage.INSTALL && zip != null);
    }

    boolean hasNotice() { return noticeVisible && phase != Phase.NONE && phase != Phase.CHECKING; }
    String version() { return release == null ? "" : release.version(); }
    int progressPercent() { return total <= 0 ? 0 : (int) Math.min(100, Math.floor(bytes * 100.0 / total)); }

    String miniText() {
        if (!hasNotice()) return "";
        return switch (phase) {
            case AVAILABLE -> "Update available · Review";
            case DOWNLOADING -> "Downloading " + progressPercent() + "% · Review";
            case READY -> "Update ready · Review";
            case ISSUE -> "Update issue · Review";
            case INSTALLING -> "Updating · Review";
            default -> "";
        };
    }
}
