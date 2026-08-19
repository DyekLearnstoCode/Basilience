package com.example.basilience;

import java.util.regex.Pattern;

/**
 * Single source of truth for Philippine mobile number validation and
 * normalization. Canonical storage format is E.164-style: +639XXXXXXXXX.
 */
public final class PhoneNumberUtils {

    public static final String INVALID_MESSAGE = "Enter a valid Philippine mobile number.";

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d+");

    private PhoneNumberUtils() {
    }

    /**
     * Normalizes accepted input styles (09171234567, 9171234567, 639171234567,
     * +639171234567 — optionally with spaces/hyphens) to +639XXXXXXXXX.
     * Returns null if the input is not a structurally valid Philippine mobile number.
     */
    public static String normalizePhilippineMobile(String input) {
        if (input == null) return null;

        String cleaned = SEPARATORS.matcher(input.trim()).replaceAll("");
        if (cleaned.isEmpty()) return null;

        boolean hasPlus = cleaned.startsWith("+");
        String digits = hasPlus ? cleaned.substring(1) : cleaned;

        if (digits.isEmpty() || !DIGITS_ONLY.matcher(digits).matches()) {
            return null;
        }

        String subscriber;
        if (digits.startsWith("63") && digits.length() == 12) {
            subscriber = digits.substring(2);
        } else if (!hasPlus && digits.startsWith("0") && digits.length() == 11) {
            subscriber = digits.substring(1);
        } else if (!hasPlus && digits.length() == 10) {
            subscriber = digits;
        } else {
            return null;
        }

        if (subscriber.length() != 10 || subscriber.charAt(0) != '9') {
            return null;
        }

        return "+63" + subscriber;
    }

    public static boolean isValidPhilippineMobile(String input) {
        return normalizePhilippineMobile(input) != null;
    }
}
