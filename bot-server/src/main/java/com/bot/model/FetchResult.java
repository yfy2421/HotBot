package com.bot.model;

import java.util.List;

public record FetchResult<T>(T data, List<SystemAlert> alerts) {

    public static <T> FetchResult<T> of(T data) {
        return new FetchResult<>(data, List.of());
    }

    public static <T> FetchResult<T> of(T data, List<SystemAlert> alerts) {
        return new FetchResult<>(data, alerts == null ? List.of() : List.copyOf(alerts));
    }
}