package com.example.basilience.models;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.example.basilience.HardwareComponentKey;

import java.util.Collections;
import java.util.List;

/**
 * One instructional section of a User Guide screen (Mobile App Guide or
 * Hardware/System Guide). Presentation-only data - never touches app state,
 * Firebase, or business logic.
 *
 * <p>{@code imageResId} is 0 until a real screenshot/photo resource exists for
 * this section; the guide renders a labeled placeholder box in that case
 * (see {@code imagePlaceholderCaption}) instead of a broken/blank image, so a
 * real asset can be dropped in later just by setting this field.
 */
public class GuideSection {

    private final String title;
    private final String description;
    @DrawableRes
    private final int imageResId;
    private final String imagePlaceholderCaption;
    private final List<String> steps;
    private final String tip;
    private final String warning;
    private final String roleLabel;
    private final boolean adminOnly;
    private final HardwareComponentKey hardwareKey;
    private final String purpose;
    private final List<String> indicators;
    private final List<String> commonProblems;
    private final List<String> troubleshooting;
    private final String imageUrl;
    private final String videoTitle;
    private final String videoDescription;
    private final String videoUrl;
    private final String videoThumbnailUrl;

    private GuideSection(Builder b) {
        this.title = b.title;
        this.description = b.description;
        this.imageResId = b.imageResId;
        this.imagePlaceholderCaption = b.imagePlaceholderCaption;
        this.steps = b.steps == null ? Collections.emptyList() : b.steps;
        this.tip = b.tip;
        this.warning = b.warning;
        this.roleLabel = b.roleLabel;
        this.adminOnly = b.adminOnly;
        this.hardwareKey = b.hardwareKey;
        this.purpose = b.purpose;
        this.indicators = b.indicators == null ? Collections.emptyList() : b.indicators;
        this.commonProblems = b.commonProblems == null ? Collections.emptyList() : b.commonProblems;
        this.troubleshooting = b.troubleshooting == null ? Collections.emptyList() : b.troubleshooting;
        this.imageUrl = b.imageUrl;
        this.videoTitle = b.videoTitle;
        this.videoDescription = b.videoDescription;
        this.videoUrl = b.videoUrl;
        this.videoThumbnailUrl = b.videoThumbnailUrl;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    @DrawableRes
    public int getImageResId() { return imageResId; }
    public String getImagePlaceholderCaption() { return imagePlaceholderCaption; }
    public List<String> getSteps() { return steps; }
    public String getTip() { return tip; }
    public String getWarning() { return warning; }
    public String getRoleLabel() { return roleLabel; }
    /** True if this section should be hidden entirely from non-Admin accounts (see MobileGuideFragment). */
    public boolean isAdminOnly() { return adminOnly; }
    /** Which physical component this section documents, or null for a system-wide/cross-cutting section. */
    @Nullable
    public HardwareComponentKey getHardwareKey() { return hardwareKey; }
    /** Short "what this is and why it matters" paragraph - the structured-section counterpart to {@link #getDescription()}. */
    public String getPurpose() { return purpose; }
    /** What the user should observe to tell this component's state - rendered as a bullet list. */
    public List<String> getIndicators() { return indicators; }
    /** Known failure modes for this component - rendered as a bullet list. */
    public List<String> getCommonProblems() { return commonProblems; }
    /** Actionable steps to resolve a problem with this component - rendered as a numbered list. */
    public List<String> getTroubleshooting() { return troubleshooting; }
    /** Admin-uploaded replacement photo (HTTPS download URL), or null to use the bundled {@link #getImageResId()}/placeholder. */
    @Nullable
    public String getImageUrl() { return imageUrl; }

    /** Heading for this section's video card (e.g. "Video Tutorial"), or null if this section has no video card at all. */
    @Nullable
    public String getVideoTitle() { return videoTitle; }
    /** Short caption shown under the video title, in both the Coming Soon and playable states. */
    @Nullable
    public String getVideoDescription() { return videoDescription; }
    /** Where to open the real tutorial video, or null/blank while none exists yet (renders as "Coming Soon"). */
    @Nullable
    public String getVideoUrl() { return videoUrl; }
    /** Optional preview image for the video card; not required for the Coming Soon or playable states to render correctly. */
    @Nullable
    public String getVideoThumbnailUrl() { return videoThumbnailUrl; }
    /** True once a real video URL has been provided - false renders the video card as "Coming Soon" instead of a player. */
    public boolean hasVideo() { return videoUrl != null && !videoUrl.trim().isEmpty(); }
    /** True if this section has a video card at all (Coming Soon or playable). */
    public boolean hasVideoCard() { return videoTitle != null; }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /** A Builder pre-populated with this section's current values, for producing an admin-override copy without restating every untouched field. */
    public Builder toBuilder() {
        return new Builder(title)
                .description(description)
                .image(imageResId)
                .imagePlaceholder(imagePlaceholderCaption)
                .steps(steps)
                .tip(tip)
                .warning(warning)
                .role(roleLabel)
                .adminOnly(adminOnly)
                .hardwareKey(hardwareKey)
                .purpose(purpose)
                .indicators(indicators)
                .commonProblems(commonProblems)
                .troubleshooting(troubleshooting)
                .imageUrl(imageUrl)
                .video(videoTitle, videoDescription)
                .videoUrl(videoUrl)
                .videoThumbnailUrl(videoThumbnailUrl);
    }

    public static class Builder {
        private final String title;
        private String description;
        @DrawableRes
        private int imageResId = 0;
        private String imagePlaceholderCaption;
        private List<String> steps;
        private String tip;
        private String warning;
        private String roleLabel;
        private boolean adminOnly;
        private HardwareComponentKey hardwareKey;
        private String purpose;
        private List<String> indicators;
        private List<String> commonProblems;
        private List<String> troubleshooting;
        private String imageUrl;
        private String videoTitle;
        private String videoDescription;
        private String videoUrl;
        private String videoThumbnailUrl;

        private Builder(String title) {
            this.title = title;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Real image resource. Leave unset (0) until the asset exists. */
        public Builder image(@DrawableRes int imageResId) {
            this.imageResId = imageResId;
            return this;
        }

        /** What the eventual screenshot/photo should show. Always set this, even once {@link #image} is set, so the caption stays available for reference. */
        public Builder imagePlaceholder(String caption) {
            this.imagePlaceholderCaption = caption;
            return this;
        }

        public Builder steps(List<String> steps) {
            this.steps = steps;
            return this;
        }

        public Builder tip(String tip) {
            this.tip = tip;
            return this;
        }

        public Builder warning(String warning) {
            this.warning = warning;
            return this;
        }

        /** e.g. "Admin Only". Leave unset for features available to every role. */
        public Builder role(String roleLabel) {
            this.roleLabel = roleLabel;
            return this;
        }

        /** Hides this section entirely from non-Admin accounts, rather than just labeling it. */
        public Builder adminOnly(boolean adminOnly) {
            this.adminOnly = adminOnly;
            return this;
        }

        /** Tags this section as documenting one specific physical component, enabling deep-link scroll/highlight from a notification. */
        public Builder hardwareKey(HardwareComponentKey hardwareKey) {
            this.hardwareKey = hardwareKey;
            return this;
        }

        /** Short "what this is and why it matters" paragraph. Use for a restructured component section instead of {@link #description}. */
        public Builder purpose(String purpose) {
            this.purpose = purpose;
            return this;
        }

        /** What the user should observe to tell this component's current state (e.g. a status line, a sound, a reading). */
        public Builder indicators(List<String> indicators) {
            this.indicators = indicators;
            return this;
        }

        /** Known failure modes for this component. */
        public Builder commonProblems(List<String> commonProblems) {
            this.commonProblems = commonProblems;
            return this;
        }

        /** Actionable steps to try when this component has a problem. */
        public Builder troubleshooting(List<String> troubleshooting) {
            this.troubleshooting = troubleshooting;
            return this;
        }

        /** Admin-uploaded replacement photo (HTTPS download URL). Leave unset to use the bundled {@link #image}/{@link #imagePlaceholder}. */
        public Builder imageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
            return this;
        }

        /** Adds a video card to this section: title/description are shown whether or not a real video exists yet. Pass a URL separately via {@link #videoUrl}. */
        public Builder video(String videoTitle, String videoDescription) {
            this.videoTitle = videoTitle;
            this.videoDescription = videoDescription;
            return this;
        }

        /** Where the real tutorial video lives. Leave unset (or blank) to render the video card as "Coming Soon" - never a broken/empty player. */
        public Builder videoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
            return this;
        }

        /** Optional preview image for the video card. Safe to leave unset in either the Coming Soon or playable state. */
        public Builder videoThumbnailUrl(String videoThumbnailUrl) {
            this.videoThumbnailUrl = videoThumbnailUrl;
            return this;
        }

        public GuideSection build() {
            return new GuideSection(this);
        }
    }
}
