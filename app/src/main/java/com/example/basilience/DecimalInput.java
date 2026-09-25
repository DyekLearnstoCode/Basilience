package com.example.basilience;

import java.text.DecimalFormatSymbols;

/**
 * Parses a decimal number a person typed or that this app displayed.
 *
 * Numeric fields are shown with the phone's own formatting ("5,50" on a
 * comma-decimal phone, native digits on some languages), and the keyboard
 * produces the same. Float.parseFloat() only understands ASCII digits and a
 * dot, so the value the screen itself displayed could not be read back. The
 * stored number is always a plain float/double; only this reading step
 * adapts to the phone.
 *
 * Accepted: an optional leading sign, digits in any script, and exactly one
 * decimal separator that is either "." or this phone's decimal separator.
 * Anything else returns null instead of guessing: empty input, letters,
 * exponents, spaces or grouping separators inside the number, two separators
 * ("1.234,5"), and a dot on a comma-decimal phone that reads as thousands
 * grouping ("1.234").
 */
final class DecimalInput {

    private DecimalInput() {}

    /** The parsed value, or null when the text is not one unambiguous finite number. */
    static Double parse(CharSequence raw) {
        return parse(raw, DecimalFormatSymbols.getInstance());
    }

    static Float parseFloat(CharSequence raw) {
        Double value = parse(raw);
        if (value == null) return null;
        float asFloat = value.floatValue();
        return Float.isNaN(asFloat) || Float.isInfinite(asFloat) ? null : asFloat;
    }

    static Double parse(CharSequence raw, DecimalFormatSymbols symbols) {
        if (raw == null) return null;
        String text = raw.toString().trim();
        if (text.isEmpty()) return null;

        char localSeparator = symbols.getDecimalSeparator();
        char localMinus = symbols.getMinusSign();

        StringBuilder normalized = new StringBuilder(text.length());
        int separatorIndex = -1;
        char separatorUsed = 0;
        boolean sawDigit = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.digit(c, 10);
            if (digit >= 0) {
                normalized.append((char) ('0' + digit));
                sawDigit = true;
            } else if (c == '.' || c == localSeparator) {
                if (separatorIndex >= 0) return null; // two separators: ambiguous
                separatorIndex = normalized.length();
                separatorUsed = c;
                normalized.append('.');
            } else if (i == 0 && (c == '-' || c == '−' || c == localMinus)) {
                normalized.append('-');
            } else if (i == 0 && c == '+') {
                // leading plus carries no information
            } else {
                return null;
            }
        }
        if (!sawDigit) return null;

        // "1.234" on a phone whose decimal separator is a comma reads as
        // thousands grouping to its user, not as 1.234.
        if (separatorUsed == '.' && localSeparator != '.') {
            int digitsAfter = normalized.length() - separatorIndex - 1;
            int digitsBefore = separatorIndex - (normalized.charAt(0) == '-' ? 1 : 0);
            if (digitsAfter == 3 && digitsBefore >= 1 && digitsBefore <= 3) return null;
        }

        try {
            double value = Double.parseDouble(normalized.toString());
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
