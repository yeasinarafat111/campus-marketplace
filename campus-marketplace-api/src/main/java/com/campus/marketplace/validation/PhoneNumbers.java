package com.campus.marketplace.validation;

import java.util.regex.Pattern;

/** Normalizes Bangladeshi mobile numbers to E.164 ({@code +8801XXXXXXXXX}). */
public final class PhoneNumbers {

    /** A normalized mobile number: +8801, an operator digit 3-9, then 8 more digits. */
    public static final Pattern E164_BD_MOBILE = Pattern.compile("^\\+8801[3-9]\\d{8}$");

    private PhoneNumbers() {
    }

    /**
     * Strips spaces and dashes, then turns {@code 01XXXXXXXXX} and {@code 8801…} into {@code +8801…}.
     * Anything else is returned stripped but otherwise unchanged, so it fails {@link #isValid}.
     * Null stays null.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replaceAll("[\\s-]", "");
        if (value.startsWith("01")) {
            return "+880" + value.substring(1);
        }
        if (value.startsWith("8801")) {
            return "+" + value;
        }
        return value;
    }

    /** True if {@code raw} is a valid Bangladeshi mobile number in any accepted input format. */
    public static boolean isValid(String raw) {
        String normalized = normalize(raw);
        return normalized != null && E164_BD_MOBILE.matcher(normalized).matches();
    }
}
