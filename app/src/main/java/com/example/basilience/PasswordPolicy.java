package com.example.basilience;

import androidx.annotation.Nullable;

/**
 * The single password policy for every Basilience account creation and
 * password change flow.
 *
 * Admin registration, Add Personnel, Admin Change Password and Personnel
 * Change Password each used to carry their own "at least 6 characters" check
 * with its own wording, so the rules could drift apart per screen and per
 * role. All four now call {@link #validate(String)}, and all four show
 * {@link #REQUIREMENTS} as helper text before the user submits.
 *
 * This governs the new password only. Confirm-password matching stays a
 * separate check owned by each screen, because a mismatch is a different
 * problem from a weak password and belongs on a different field.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    /** Shown as helper text under the new-password field before submission. */
    public static final String REQUIREMENTS =
            "Password must contain at least:\n"
                    + "• 8 characters\n"
                    + "• 1 uppercase letter\n"
                    + "• 1 lowercase letter\n"
                    + "• 1 number\n"
                    + "• 1 special character";

    private PasswordPolicy() {}

    /**
     * Checks a proposed password against the policy.
     *
     * @return {@code null} when the password is acceptable, otherwise a
     *         ready-to-display message naming the single unmet requirement.
     *         The message is written for the person typing it - Firebase Auth
     *         error text is never surfaced through here.
     */
    @Nullable
    public static String validate(@Nullable String password) {
        if (password == null || password.isEmpty()) {
            return "Password is required";
        }
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters";
        }

        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (!Character.isWhitespace(c)) {
                // Anything printable that is not a letter, digit or space
                // counts - the policy asks for a special character, not for
                // one from a fixed list.
                hasSpecial = true;
            }
        }

        if (!hasUpper) return "Password must include at least 1 uppercase letter";
        if (!hasLower) return "Password must include at least 1 lowercase letter";
        if (!hasDigit) return "Password must include at least 1 number";
        if (!hasSpecial) return "Password must include at least 1 special character";

        return null;
    }

    /** Convenience for callers that only need the yes/no answer. */
    public static boolean isValid(@Nullable String password) {
        return validate(password) == null;
    }
}
