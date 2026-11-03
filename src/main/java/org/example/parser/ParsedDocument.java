package org.example.parser;

import java.util.SortedMap;

/** Результат разбора одного вложения. */
public record ParsedDocument(
        String fileName,
        String detectedType,
        long sizeBytes,
        String sha256,
        long elapsedMs,
        boolean success,
        boolean truncated,
        String error,
        SortedMap<String, String> metadata,
        String text) {
}