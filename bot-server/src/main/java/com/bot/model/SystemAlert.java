package com.bot.model;

public record SystemAlert(String severity, String source, String code, String message) {

    public static SystemAlert warn(String source, String code, String message) {
        return new SystemAlert("WARN", source, code, message);
    }

    public static SystemAlert error(String source, String code, String message) {
        return new SystemAlert("ERROR", source, code, message);
    }
}