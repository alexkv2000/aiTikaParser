package org.example.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.example.chat.ChatFormatter;
import org.example.chat.ChatPage;
import org.example.parser.DocumentParserService;
import org.example.parser.OcrEnv;
import org.example.parser.OcrOptions;
import org.example.parser.ParsedDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;

/** Встроенный REST-сервер «web-чата» на com.sun.net.httpserver (только JDK). */
public final class ChatServer {

    private final int port;
    private final DocumentParserService parserService;
    private final ChatFormatter formatter = new ChatFormatter();
    private HttpServer server;

    public ChatServer(int port, DocumentParserService parserService) {
        this.port = port;
        this.parserService = parserService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/chat/message", this::handleChatMessage);
        server.createContext("/api/parse", this::handleChatMessage);          // альтернативный путь
        server.createContext("/health", ex -> sendText(ex, 200, "OK; tesseract=" + ocrStatus()));
        server.createContext("/", ex -> sendBytes(ex, 200, "text/html; charset=utf-8",
                ChatPage.HTML.getBytes(StandardCharsets.UTF_8)));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /* ────────────────────────── обработка чата ────────────────────────── */

    private void handleChatMessage(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Ожидается POST с multipart/form-data (поля: files — вложения, " +
                        "message — текст, meta=true/false — выдача метаданных).");
                return;
            }

            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            byte[] body = ex.getRequestBody().readAllBytes();

            List<MultipartParser.Part> parts;
            if (MultipartParser.isMultipart(contentType)) {
                parts = MultipartParser.parse(body, contentType);
            } else {
                String name = queryParam(ex, "filename").orElse("upload.bin");
                parts = List.of(new MultipartParser.Part("file", name, contentType, body));
            }

            List<MultipartParser.Part> files = parts.stream()
                    .filter(MultipartParser.Part::isFile)
                    .toList();
            if (files.isEmpty()) {
                sendText(ex, 400, "В запросе нет файлов-вложений.\nПример: " +
                        "curl -F \"files=@doc.pdf\" http://localhost:" + port +
                        "/api/chat/message?meta=false");
                return;
            }

            String message = textField(parts, "message")
                    .or(() -> textField(parts, "text"))
                    .orElse(null);

            int maxChars = queryParam(ex, "maxChars").map(Integer::parseInt)
                    .orElse(parserService.defaultWriteLimit());

            // ── ПРИЗНАК ВЫДАЧИ МЕТАДАННЫХ ──
            // приоритет: query-параметр ?meta= / ?metadata= → поле формы meta=... → true по умолчанию
            boolean includeMetadata = resolveFlag(ex, parts, "meta", true);
            // Явная кодировка текстового файла (напр. ?charset=GBK для legacy-файлов).
            // Применяется ко всем файлам запроса; для бинарных (docx/pdf) не передавайте.
            String charset = queryParam(ex, "charset").orElse("");

            // ── OCR (Tesseract): сканы и картинки, включая встроенные в doc/docx/pdf ──
            // ?ocr=false — выключить; ?ocrLang=rus+chi_sim+eng — языки; ?ocrScan=true — сканы PDF
            boolean ocrEnabled = resolveFlag(ex, parts, "ocr", true);
            String ocrLang = queryParam(ex, "ocrLang")
                    .or(() -> textField(parts, "ocrLang"))
                    .orElse("rus+eng")
                    .replace(' ', '+');   // «+» в URL мог прийти как пробел после декодирования
            boolean ocrScan = resolveFlag(ex, parts, "ocrScan", false);
            OcrOptions ocrOptions = new OcrOptions(ocrEnabled, ocrLang, ocrScan);

            long started = System.currentTimeMillis();
            StringBuilder out =
                    new StringBuilder(formatter.chatHeader(message, files.size(), includeMetadata));

            int ok = 0;
            int failed = 0;
            int index = 1;
            for (MultipartParser.Part file : files) {
                byte[] content = transcodeToUtf8(file.content(), charset);
                ParsedDocument doc = parserService.parse(content, file.fileName(), maxChars, ocrOptions);
                if (doc.success()) ok++; else failed++;
                out.append(formatter.formatAttachment(doc, index++, files.size(), includeMetadata));
            }
            out.append(formatter.chatFooter(ok, failed, System.currentTimeMillis() - started));

            sendText(ex, 200, out.toString());

        } catch (NumberFormatException e) {
            sendText(ex, 400, "Параметр maxChars должен быть целым числом.");
        } catch (java.nio.charset.UnsupportedCharsetException e) {
            sendText(ex, 400, "Неизвестная кодировка: " + e.getMessage()
                    + " (примеры: GBK, GB18030, GB2312, Big5, UTF-8)");
        } catch (IllegalArgumentException e) {
            sendText(ex, 400, "Некорректный запрос: " + e.getMessage());
        } catch (Exception e) {
            sendText(ex, 500, "Внутренняя ошибка сервера: " + e);
        } finally {
            ex.close();
        }
    }

    /** Перекодирует текстовый файл в UTF-8, если явно указана кодировка (?charset=GBK и т.п.). */
    private static byte[] transcodeToUtf8(byte[] content, String charset) {
        if (charset == null || charset.isBlank()) {
            return content;
        }
        return new String(content, java.nio.charset.Charset.forName(charset))
                .getBytes(StandardCharsets.UTF_8);
    }

    /* ────────────────────────── утилиты ────────────────────────── */

    /** Статус Tesseract для /health: где найден и хватает ли языков. */
    private static String ocrStatus() {
        return OcrEnv.status();
    }

    /** Значение текстового (не файлового) поля формы. */
    private static Optional<String> textField(List<MultipartParser.Part> parts, String name) {
        return parts.stream()
                .filter(p -> !p.isFile() && name.equalsIgnoreCase(p.fieldName()))
                .map(p -> new String(p.content(), StandardCharsets.UTF_8))
                .findFirst();
    }

    /** Булев признак: query-параметр → поле формы → значение по умолчанию. */
    private static boolean resolveFlag(HttpExchange ex, List<MultipartParser.Part> parts,
                                       String name, boolean defaultValue) {
        String value = queryParam(ex, name)
                .or(() -> queryParam(ex, name + "data"))   // meta → metadata
                .or(() -> textField(parts, name))
                .orElse(null);
        return parseFlag(value, defaultValue);
    }

    /** Разбирает true/false, 1/0, on/off, yes/no; пустое/нечитаемое — значение по умолчанию. */
    static boolean parseFlag(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) {
            return true;
        }
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) {
            return false;
        }
        return defaultValue;
    }

    private static void sendText(HttpExchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, String contentType, byte[] body)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static Optional<String> queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }
}