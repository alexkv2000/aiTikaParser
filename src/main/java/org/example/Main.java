package org.example;

import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.example.parser.DocumentParserService;
import org.example.parser.OcrEnv;
import org.example.server.ChatServer;

import java.io.IOException;

public final class Main {

    public static void main(String[] args) {
        int port = intArgOrEnv(args, 0, "PORT", 8081);
        int maxForks = intEnv("MAX_FORKS", 4);
        int maxChars = intEnv("MAX_CHARS", 100_000);

        DocumentParserService parserService;
        try {
            parserService = new DocumentParserService(maxForks, maxChars);
        } catch (TikaConfigException | IOException e) {
            System.err.println("Не удалось инициализировать PipesForkParser: " + e.getMessage());
            System.exit(1);
            return;
        }

        ChatServer server = new ChatServer(port, parserService);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Не удалось запустить сервер на порту " + port + ": " + e.getMessage());
            parserService.close();
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            parserService.close();
        }));

        System.out.print(banner(port, maxForks, maxChars));
        if (!ocrAvailable()) {
            System.err.println("⚠ Tesseract не найден: " + OcrEnv.status());
        }
    }

    /** Доступен ли бинарник Tesseract (для предупреждения при старте). */
    /** Доступен ли Tesseract: наша проверка (env/PATH/стандартные каталоги) + проверка Tika. */
    private static boolean ocrAvailable() {
        if (OcrEnv.tesseractBinary().isPresent()) {
            return true;
        }
        try {
            return new TesseractOCRParser().hasTesseract();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int intArgOrEnv(String[] args, int idx, String env, int def) {
        if (args.length > idx) {
            try { return Integer.parseInt(args[idx]); } catch (NumberFormatException ignored) { }
        }
        String v = System.getenv(env);
        if (v != null) {
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static int intEnv(String env, int def) {
        String v = System.getenv(env);
        if (v != null) {
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static String banner(int port, int maxForks, int maxChars) {
        return """

                ══════════════════════════════════════════════════════
                  AI TIKA PARSER — разбор вложений web-чата
                ══════════════════════════════════════════════════════
                  Web-чат (браузер) : http://localhost:%d/
                  REST-запрос       : POST http://localhost:%d/api/chat/message
                  Health-check      : GET  http://localhost:%d/health

                  Форк-процессов (numClients): %d
                  Лимит текста по умолчанию  : %d символов
                         (?maxChars=N — лимит текста, 0 = без лимита;
                          ?meta=false — отключить выдачу метаданных;
                          ?charset=GBK — кодировка legacy .txt;
                          ?ocr=false — выключить OCR;
                          ?ocrLang=rus+chi_sim — языки распознавания;
                          ?ocrScan=true — постраничный OCR сканов PDF)

                  Пример (curl):
                    curl -s -F "message=Разбери вложения" \\
                         -F "files=@otchet.docx" \\
                         http://localhost:%d/api/chat/message
                ══════════════════════════════════════════════════════
                """.formatted(port, port, port, maxForks, maxChars, port);
    }
}