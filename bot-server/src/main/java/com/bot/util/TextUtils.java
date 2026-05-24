package com.bot.util;

public final class TextUtils {

    private TextUtils() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }
}