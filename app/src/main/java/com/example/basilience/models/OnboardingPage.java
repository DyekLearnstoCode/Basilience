package com.example.basilience.models;

import androidx.annotation.DrawableRes;

/**
 * One page of the first-install onboarding walkthrough. Presentation-only -
 * never touches auth, role, or Firebase state.
 *
 * <p>{@code imageResId} is 0 for pages whose real screenshot doesn't exist
 * yet, in which case a labeled placeholder is rendered instead (same
 * convention as {@link GuideSection}). Screens 1 and 6 use the app's real,
 * already-bundled logo ({@code R.drawable.basilience_logo}) rather than a
 * placeholder, since that's a genuine existing asset, not an invented one.
 */
public class OnboardingPage {

    private final String title;
    private final String description;
    @DrawableRes
    private final int imageResId;
    private final String imagePlaceholderCaption;
    private final String roleNote;

    public OnboardingPage(String title, String description, @DrawableRes int imageResId,
                           String imagePlaceholderCaption, String roleNote) {
        this.title = title;
        this.description = description;
        this.imageResId = imageResId;
        this.imagePlaceholderCaption = imagePlaceholderCaption;
        this.roleNote = roleNote;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    @DrawableRes
    public int getImageResId() { return imageResId; }
    public String getImagePlaceholderCaption() { return imagePlaceholderCaption; }
    /** e.g. "Admin Only". Null for pages with no role-specific note. */
    public String getRoleNote() { return roleNote; }
}
