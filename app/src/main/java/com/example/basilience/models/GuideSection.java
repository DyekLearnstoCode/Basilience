package com.example.basilience.models;

import androidx.annotation.DrawableRes;

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

    public static Builder builder(String title) {
        return new Builder(title);
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

        public GuideSection build() {
            return new GuideSection(this);
        }
    }
}
