package org.example;

import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.example.parser.DocumentParserService;
import org.example.parser.OcrEnv;
import org.example.server.ChatServer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Точка входа: читает настройки (args/env), собирает парсер и поднимает HTTP-сервер.
 */
public final class Main {

    /** java.util.logging — без внешних зависимостей; по умолчанию WARNING+ идут в stderr. */
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        int port = intArgOrEnv(args, 0, "PORT", 8081);
        int maxForks = intEnv("MAX_FORKS", 4);
        int maxChars = intEnv("MAX_CHARS", 100_000);

        DocumentParserService parserService;
        try {
            parserService = new DocumentParserService(maxForks, maxChars);
        } catch (TikaConfigException | IOException e) {
            LOG.log(Level.SEVERE, "Не удалось инициализировать PipesForkParser: {0}", e.getMessage());
            System.exit(1);
            return;
        }

        ChatServer server = new ChatServer(port, parserService);
        try {
            server.start();
        } catch (IOException e) {
            LOG.severe(() -> "Не удалось запустить сервер на порту " + port + ": " + e.getMessage());
            parserService.close();
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            parserService.close();
        }));

        printBanner(port, maxForks, maxChars);
        if (!ocrAvailable()) {
            LOG.warning(() -> "Tesseract не найден: " + OcrEnv.status());
        }
    }

    /**
     * Баннер о запуске — намеренный вывод в консоль (UI приложения), а не логирование.
     * Поэтому S106 подавлен точечно и только здесь.
     */
    @SuppressWarnings("java:S106")
    private static void printBanner(int port, int maxForks, int maxChars) {
        System.out.print(banner(port, maxForks, maxChars));
    }

    /** Доступен ли Tesseract: наша проверка (env/PATH/стандартные каталоги) + проверка Tika. */
    private static boolean ocrAvailable() {
        if (OcrEnv.tesseractBinary().isPresent()) {
            return true;
        }
        // TesseractOCRParser — Closeable, поэтому try-with-resources (S2095)
        try (TesseractOCRParser tika = new TesseractOCRParser()) {
            return tika.hasTesseract();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Проверка Tika (TesseractOCRParser) не удалась", e);
            return false;
        }
    }

    /** Целое из строки или null, если строка null/некорректна. */
    private static Integer tryParse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.fine(() -> "Некорректное целое '" + raw + "', использую значение по умолчанию");
            return null;
        }
    }

    /** Приоритет: аргумент CLI → переменная окружения → значение по умолчанию. */
    private static int intArgOrEnv(String[] args, int idx, String env, int def) {
        Integer arg = args.length > idx ? tryParse(args[idx]) : null;
        return arg != null ? arg : intEnv(env, def);
    }

    private static int intEnv(String env, int def) {
        Integer parsed = tryParse(System.getenv(env));
        return parsed != null ? parsed : def;
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
                          ?ocrLang=rus+eng — языки распознавания;
                          ?ocrScan=true — постраничный OCR сканов PDF)

                  Пример (curl):
                    curl -s -F "message=Разбери вложения" \\
                         -F "files=@otchet.docx" \\
                         http://localhost:%d/api/chat/message
                ══════════════════════════════════════════════════════
                """.formatted(port, port, port, maxForks, maxChars, port);
    }
}