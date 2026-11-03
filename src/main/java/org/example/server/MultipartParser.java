package org.example.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Простейший разбор multipart/form-data без внешних зависимостей.
 */
public final class MultipartParser {

    /** Одна часть запроса: текстовое поле формы или файл-вложение. */
    public record Part(String fieldName, String fileName, String contentType, byte[] content) {
        public boolean isFile() {
            return fileName != null && !fileName.isBlank();
        }
        /**
         * По значению, включая содержимое byte[]: автогенерированный equals
         * рекорда сравнивает массив по ссылке.
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof Part other
                    && Objects.equals(fieldName, other.fieldName)
                    && Objects.equals(fileName, other.fileName)
                    && Objects.equals(contentType, other.contentType)
                    && Arrays.equals(content, other.content);
        }
        @Override
        public int hashCode() {
            int result = Objects.hashCode(fieldName);
            result = 31 * result + Objects.hashCode(fileName);
            result = 31 * result + Objects.hashCode(contentType);
            result = 31 * result + Arrays.hashCode(content);
            return result;
        }
        @Override
        public String toString() {
            return "Part[" + fieldName
                    + ", file=" + fileName
                    + ", type=" + contentType
                    + ", " + content.length + " bytes]";
        }
    }

    private static final Pattern BOUNDARY =
            Pattern.compile("boundary\\s*=\\s*\"?([^\";]++)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_NAME =
            Pattern.compile("name\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*+)\"");
    private static final Pattern FILE_NAME =
            Pattern.compile("filename\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*+)\"");

    private static final byte[] CR_LF          = {'\r', '\n'};
    private static final byte[] HEADERS_END    = {'\r', '\n', '\r', '\n'};
    private static final byte[] HEADERS_END_NX = {'\n', '\n'};
    private static final byte[] CLOSING        = {'-', '-'};

    private MultipartParser() { }

    public static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    public static List<Part> parse(byte[] body, String contentTypeHeader) {
        String boundary = extractBoundary(contentTypeHeader);
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);

        List<Part> parts = new ArrayList<>();
        int position = indexOf(body, delimiter, 0);
        while (position >= 0) {
            int afterDelimiter = position + delimiter.length;

            if (startsWith(body, afterDelimiter, CLOSING)) {
                break;                                    // достигли --boundary--
            }
            int contentStart = skip(body, afterDelimiter, CR_LF);

            int next = indexOf(body, delimiter, contentStart);
            int end = next < 0 ? body.length : next;
            if (endsWith(body, contentStart, end, CR_LF)) {
                end -= CR_LF.length;                      // \r\n перед следующим boundary
            }
            if (end > contentStart) {
                parts.add(parsePart(Arrays.copyOfRange(body, contentStart, end)));
            }
            position = next;
        }
        return parts;
    }

    /* ────────────────────────── внутреннее ────────────────────────── */

    private static String extractBoundary(String contentTypeHeader) {
        Matcher matcher = BOUNDARY.matcher(contentTypeHeader == null ? "" : contentTypeHeader);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "в Content-Type отсутствует multipart boundary: " + contentTypeHeader);
        }
        return matcher.group(1).trim();
    }

    private static Part parsePart(byte[] raw) {
        int split = indexOf(raw, HEADERS_END, 0);
        int separatorLength = HEADERS_END.length;
        if (split < 0) {
            split = indexOf(raw, HEADERS_END_NX, 0);
            separatorLength = HEADERS_END_NX.length;
        }
        String headers = split < 0 ? "" : new String(raw, 0, split, StandardCharsets.UTF_8);
        byte[] content = split < 0 ? raw : Arrays.copyOfRange(raw, split + separatorLength, raw.length);

        String disposition = headerValue(headers, "Content-Disposition");
        String contentType = headerValue(headers, "Content-Type");

        String fieldName = firstGroup(disposition, FIELD_NAME, "file");
        String fileName  = firstGroup(disposition, FILE_NAME, null);
        if (fileName != null) {
            fileName = unescape(fileName);
            int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
            fileName = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        }
        return new Part(fieldName, fileName, contentType, content);
    }

    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String firstGroup(String header, Pattern pattern, String defaultValue) {
        if (header == null) {
            return defaultValue;
        }
        Matcher matcher = pattern.matcher(header);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            if (matchesAt(data, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    /** Все байты pattern совпадают с data, начиная с offset (границы сверху проверяет вызывающий). */
    private static boolean matchesAt(byte[] data, int offset, byte[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] pattern) {
        return offset >= 0
                && offset + pattern.length <= data.length
                && matchesAt(data, offset, pattern);
    }

    private static boolean endsWith(byte[] data, int from, int to, byte[] pattern) {
        if (to - from < pattern.length || to > data.length) {
            return false;
        }
        int offset = to - pattern.length;
        return matchesAt(data, offset, pattern);
    }

    private static int skip(byte[] data, int offset, byte[] pattern) {
        return startsWith(data, offset, pattern) ? offset + pattern.length : offset;
    }
}