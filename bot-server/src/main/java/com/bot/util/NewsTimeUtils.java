package com.bot.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class NewsTimeUtils {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日");

    private NewsTimeUtils() {
    }

    public static Instant parseInstant(String value) {
        if (!TextUtils.hasText(value)) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    public static String formatDisplayTime(String rawTime) {
        if (!TextUtils.hasText(rawTime)) {
            return "";
        }
        try {
            return OffsetDateTime.parse(rawTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(DISPLAY_ZONE)
                    .format(DATE_TIME_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(rawTime, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(DISPLAY_ZONE)
                    .format(DATE_TIME_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(rawTime, DateTimeFormatter.ISO_DATE)
                    .format(DATE_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        return rawTime;
    }
}