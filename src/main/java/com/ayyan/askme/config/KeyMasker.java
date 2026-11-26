package com.ayyan.askme.config;

/**
 * Shared API-key masking helpers used by BOTH the configuration GUI (masked
 * text field / Show All toggle) and chat output ({@code /askme info}).
 *
 * <p>Security rule from the spec: only the FIRST TWO characters of the API key
 * are ever revealed (e.g. {@code AI***********}); every remaining character is
 * replaced by {@code *}.</p>
 */
public final class KeyMasker {

    private static final int MIN_KEY_LENGTH = 10;
    private static final int MAX_KEY_LENGTH = 256;

    private KeyMasker() {
    }

    public static String clean(String key) {
        String value = key == null ? "" : key.trim();
        boolean wrappedInQuotes = value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")));
        if (wrappedInQuotes) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    public static boolean isValid(String key) {
        String value = clean(key);
        return value.length() >= MIN_KEY_LENGTH && value.length() <= MAX_KEY_LENGTH
                && value.chars().noneMatch(Character::isWhitespace);
    }

    /**
     * Masks an API key: keeps the first 2 characters visible, replaces every
     * other character with '*'.
     *
     * @param key raw (or partially typed) key; may be null or empty
     * @return masked representation of exactly the same length, or empty string
     */
    public static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        int length = key.length();
        if (length <= 2) {
            return "*".repeat(length);
        }
        return key.substring(0, 2) + "*".repeat(length - 2);
    }

    /**
     * Chooses between masked and plain view depending on the "Show All" toggle.
     *
     * @param key   the real key value
     * @param showAll whether full reveal is requested
     * @return plain key when revealed, otherwise masked version
     */
    public static String maskUnlessShowing(String key, boolean showAll) {
        return showAll ? (key == null ? "" : key) : mask(key);
    }
}