package org.example.chat;

import org.example.parser.ParsedDocument;
import java.util.Map;
import java.util.stream.Collectors;

/** Форматирование ответа чата в текстовом виде. */
public final class ChatFormatter {

    private static final String THICK = "═".repeat(78);
    private static final String THIN  = "─".repeat(78);
    private static final int VALUE_WIDTH = 64;

    /* ── шапка чата ── */

    public String chatHeader(String userMessage, int attachments) {
        return chatHeader(userMessage, attachments, true);
    }

    public String chatHeader(String userMessage, int attachments, boolean includeMetadata) {
        StringBuilder sb = new StringBuilder();
        sb.append(THICK).append('\n')
                .append("  AI TIKA PARSER | WEB-CHAT | разбор вложений\n")
                .append(THICK).append('\n');
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append("  Пользователь : ").append(userMessage.strip()).append('\n');
        }
        sb.append("  Вложений получено: ").append(attachments).append('\n');
        sb.append("  Метаданные   : ")
                .append(includeMetadata ? "включены" : "ОТКЛЮЧЕНЫ (meta=false)").append('\n');
        sb.append('\n');
        return sb.toString();
    }

    /* ── карточка вложения ── */

    public String formatAttachment(ParsedDocument doc, int index, int total) {
        return formatAttachment(doc, index, total, true);
    }

    public String formatAttachment(ParsedDocument doc, int index, int total, boolean includeMetadata) {
        StringBuilder sb = new StringBuilder();
        sb.append(THIN).append('\n')
                .append(String.format("  ВЛОЖЕНИЕ %d/%d : %s%n", index, total, doc.fileName()))
                .append(THIN).append('\n');
              /*  .append(String.format("      Тип         : %s%n", doc.detectedType()))
                .append(String.format("      Размер      : %s (%d байт)%n",
                        humanSize(doc.sizeBytes()), doc.sizeBytes()))
                .append(String.format("      SHA-256     : %s%n", shortHash(doc.sha256())))
                .append(String.format("      Время разбора: %d мс%n", doc.elapsedMs())); */

        // Блок метаданных выводится только при включённом признаке
        if (includeMetadata && !doc.metadata().isEmpty()) {
            sb.append("      Метаданные:\n");
            for (Map.Entry<String, String> e : doc.metadata().entrySet()) {
                sb.append(String.format("        - %-32s %s%n",
                        e.getKey() + ":", truncate(e.getValue(), VALUE_WIDTH)));
            }
        }

        sb.append("      Текст документа:\n");
        if (!doc.success()) {
            sb.append(String.format("        [ОШИБКА] %s%n", doc.error()));
        } else if (doc.text().isBlank()) {
            sb.append("        (текст не извлечён — вероятно, файл без текстового слоя)\n");
        } else {
            sb.append(indent(doc.text(), "        ")).append('\n');
            if (doc.truncated()) {
                sb.append("        [текст обрезан по лимиту символов]\n");
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    public String chatFooter(int ok, int failed, long totalMs) {
        return THICK + "\n"
                + String.format("  ИТОГ: успешно %d, с ошибками %d, общее время %d мс%n",
                ok, failed, totalMs)
                + THICK + "\n";
    }

    /* ────────────────────────── утилиты ────────────────────────── */

//    private static String shortHash(String hash) {
//        return hash.length() <= 24 ? hash : hash.substring(0, 24) + "...";
//    }

    private static String indent(String text, String padding) {
        return text.lines().map(line -> padding + line).collect(Collectors.joining("\n"));
    }

    private static String truncate(String value, int maxWidth) {
        String clean = value.replace("\r", "").replace("\n", " ").strip();
        return clean.length() <= maxWidth ? clean : clean.substring(0, maxWidth - 1) + "…";
    }

//    private static String humanSize(long bytes) {
//        if (bytes < 1024) return bytes + " Б";
//        String[] units = {"КБ", "МБ", "ГБ", "ТБ"};
//        int exp = (int) (Math.log(bytes) / Math.log(1024));
//        return String.format(Locale.ROOT, "%.1f %s", bytes / Math.pow(1024, exp), units[exp - 1]);
//    }
}