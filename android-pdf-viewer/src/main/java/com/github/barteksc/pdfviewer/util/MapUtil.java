package com.github.barteksc.pdfviewer.util;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.Objects;

public class MapUtil {

    @NonNull
    public static <K, V> V getOrDefault(@NonNull Map<K, V> map, K key, @NonNull V defaultValue) {
        V t = map.get(key);
        return t == null ? Objects.requireNonNull(defaultValue) : t;
    }
}
