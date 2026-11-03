package org.example.parser;

import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;
import org.apache.tika.sax.BodyContentHandler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Разбор документов.
 * Маршрутизация:
 *  - OCR включён  → разбор в ТЕКУЩЕМ процессе (в дочернюю JVM Tika 4 не передаёт
 *    TesseractOCRConfig/PDFParserConfig, а OCR-парсер в форке не загружен).
 *  - OCR выключен → PipesForkParser (изоляция сбоев); если форк не смог вернуть
 *    текст или упал — автоматический повтор в текущем процессе.
 * Базовые настройки обоих процессов — tika-config.xml в jar.
 */
public final class DocumentParserService implements AutoCloseable {

    /** Результат одной попытки разбора (форк или текущий процесс). */
    private record Attempt(Metadata metadata, String text, boolean truncated, String error) { }

    /** Кандидаты имён сеттера количества форк-процессов. */
    private static final List<String> PROCESS_COUNT_SETTERS =
            List.of("setNumClients", "setMaxInstances", "setMaxForkedProcesses");

    /** Кандидаты имён сеттера стратегии постраничного OCR PDF (в Tika 4.x может отсутствовать). */
    private static final List<String> PDF_OCR_STRATEGY_SETTERS =
            List.of("setOcrStrategy", "setOcrRenderingStrategy");

    /** Кандидаты имён сеттера DPI рендера страниц PDF. */
    private static final List<String> PDF_OCR_DPI_SETTERS =
            List.of("setOcrDPI", "setOcrDpi");

    /** Известные имена текстовых геттеров PipesForkResult (API менялся между версиями). */
    private static final List<String> TEXT_GETTERS =
            List.of("getExtractedText", "getText", "getContent", "getBody", "getPayload",
                    "getOutput", "getHtml");

    /** Таймаут задачи в форк-процессе: 5 минут. */
    private static final long FORK_TASK_TIMEOUT_MS = 300_000L;

    private final PipesForkParser forkParser;
    private final AutoDetectParser localParser = new AutoDetectParser();
    private final Detector detector = new DefaultDetector();
    private final int defaultWriteLimit;

    public DocumentParserService(int maxConcurrentForks, int defaultWriteLimit)
            throws TikaConfigException, IOException {
        this.defaultWriteLimit = defaultWriteLimit;

        PipesForkParserConfig config = new PipesForkParserConfig();
        config.setJavaPath(javaBin());

        applyFirstAvailable(config, PROCESS_COUNT_SETTERS, Math.max(1, maxConcurrentForks));
        applyTaskTimeoutLimits(config, FORK_TASK_TIMEOUT_MS);
        // перезапуск дочерней JVM после N файлов — гигиена памяти при длительной работе
        if (!(trySet(config, "setMaxFilesPerProcess", int.class, 1000)
                || trySet(config, "setMaxFilesPerProcess", long.class, 1000L))) {
            System.err.println("WARN: setMaxFilesPerProcess не найден — дочерние процессы не перезапускаются.");
        }
        this.forkParser = new PipesForkParser(config);
    }

    public int defaultWriteLimit() {
        return defaultWriteLimit;
    }

    /** Старая сигнатура: с настройками OCR по умолчанию (вкл., rus+chi_sim). */
    public ParsedDocument parse(byte[] data, String fileName, int maxChars) {
        return parse(data, fileName, maxChars, OcrOptions.defaults());
    }

    public ParsedDocument parse(byte[] data, String fileName, int maxChars, OcrOptions ocr) {
        long startNanos = System.nanoTime();
        int limit = maxChars > 0 ? maxChars : -1;          // -1 = без ограничений

        Attempt attempt;
        if (ocr.enabled()) {
            // OCR — только в текущем процессе (см. комментарий к классу)
            attempt = parseLocally(data, fileName, limit, buildContext(ocr));
        } else {
            attempt = parseWithFork(data, fileName, limit);
            if (attempt.error() != null) {
                // резерв — текущий процесс (OCR там выключен через skipOcr)
                Attempt local = parseLocally(data, fileName, limit, buildContext(ocr));
                attempt = local.error() == null
                        ? local
                        : new Attempt(local.metadata(), local.text(), local.truncated(),
                        attempt.error() + " | in-process: " + local.error());
            }
        }

        TreeMap<String, String> meta = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : attempt.metadata().names()) {
            meta.put(name, String.join("; ", attempt.metadata().getValues(name)));
        }

        return new ParsedDocument(
                fileName,
                detectType(data, fileName),
                data.length,
                sha256(data),
                (System.nanoTime() - startNanos) / 1_000_000,
                attempt.error() == null,
                attempt.truncated(),
                attempt.error(),
                meta,
                attempt.text().strip());
    }

    /* ─────────── пути разбора ─────────── */

    /**
     * Разбор в дочерней JVM: parse(TikaInputStream, Metadata, ParseContext) → PipesForkResult.
     * ParseContext — ПУСТОЙ: Tika 4 сериализует контекст в JSON, и незарегистрированные
     * конфиги (PDFParserConfig и др.) рушат сериализацию («... is not registered»).
     * Текст из PipesForkResult достаём автопоиском; если не вышло — возвращаем «ошибку»,
     * и разбор автоматически повторится в текущем процессе.
     */
    private Attempt parseWithFork(byte[] data, String fileName, int limit) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        try (TikaInputStream in = TikaInputStream.get(data)) {
            PipesForkResult result = forkParser.parse(in, metadata, new ParseContext());

            // исключение, пришедшее из дочерней JVM
            Throwable childError = invokeGetter(result, "getException", Throwable.class);
            if (childError != null) {
                return new Attempt(metadata, "", false, describe(childError));
            }

            // метаданные из дочерней JVM
            Metadata forkMeta = result.getMetadata();
            if (forkMeta != null) {
                for (String name : forkMeta.names()) {
                    for (String value : forkMeta.getValues(name)) {
                        metadata.add(name, value);
                    }
                }
            }

            String text = extractForkText(result, forkMeta);
            if (text == null) {
                return new Attempt(metadata, "", false,
                        "PipesForkResult: не найден способ извлечь текст (API Tika 4.x)");
            }

            boolean truncated = false;
            if (limit > 0 && text.length() > limit) {
                text = text.substring(0, limit);
                truncated = true;
            }
            return new Attempt(metadata, text, truncated, null);

        } catch (Exception e) {
            if (isWriteLimitReached(e)) {
                return new Attempt(metadata, "", true, null);
            }
            return new Attempt(metadata, "", false, describe(e));
        }
    }

    /** Разбор в текущем процессе с полным контролем контекста (OCR, PDF). */
    private Attempt parseLocally(byte[] data, String fileName, int limit, ParseContext context) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        BodyContentHandler handler = new BodyContentHandler(limit);
        try (TikaInputStream in = TikaInputStream.get(data)) {
            localParser.parse(in, handler, metadata, context);
            return new Attempt(metadata, handler.toString(), false, null);
        } catch (Exception e) {
            if (isWriteLimitReached(e)) {
                return new Attempt(metadata, handler.toString(), true, null);
            }
            return new Attempt(metadata, "", false, describe(e));
        }
    }

    /* ─────────── OCR (Tesseract): настройки под конкретный запрос ─────────── */

    private ParseContext buildContext(OcrOptions ocr) {
        ParseContext context = new ParseContext();
        TesseractOCRConfig tess = new TesseractOCRConfig();

        if (!ocr.enabled()) {
            tess.setSkipOcr(true);                        // гарантированно выключить OCR
            context.set(TesseractOCRConfig.class, tess);
            return context;
        }

        tess.setLanguage(ocr.language());                 // «rus+chi_sim», «rus+chi_sim+eng», «chi_tra»…

        // Пути из env (сеттеры в Tika 4.x проверяют существование пути и бросают TikaConfigException)
        OcrEnv.tesseractBinary().ifPresent(bin -> {
            try {
                tess.setTesseractPath(bin.toString());
            } catch (TikaConfigException e) {
                System.err.println("WARN: tesseractPath (" + bin + ") отклонён: " + e.getMessage());
            }
        });
        OcrEnv.tessdataDir().ifPresent(dir -> {
            try {
                tess.setTessdataPath(dir.toString());
            } catch (TikaConfigException e) {
                System.err.println("WARN: tessdataPath (" + dir + ") отклонён: " + e.getMessage());
            }
        });
        context.set(TesseractOCRConfig.class, tess);

        // PDF: страница сканированного PDF — это встроенная картинка; извлекаем их и OCR-им
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setExtractInlineImages(true);
        pdf.setExtractUniqueInlineImagesOnly(true);
        if (ocr.scanPdfPages()) {
            boolean strategyApplied = trySetAny(pdf, PDF_OCR_STRATEGY_SETTERS, "ocr_and_text");
            trySetAny(pdf, PDF_OCR_DPI_SETTERS, 150);
            if (!strategyApplied) {
                System.err.println("WARN: постраничный рендер PDF недоступен в этой версии Tika; " +
                        "сканы PDF обрабатываются через OCR встроенных картинок.");
            }
        }
        context.set(PDFParserConfig.class, pdf);
        return context;
    }

    @Override
    public void close() {
        try {
            forkParser.close();
        } catch (Exception e) {
            System.err.println("Ошибка остановки fork-парсера: " + e.getMessage());
        }
    }

    /* ─────────── версионо-зависимые вызовы API Tika 4.x ─────────── */

    /**
     * Достаёт текст из PipesForkResult, не привязываясь к конкретному имени геттера:
     * 1) известные имена; 2) любой String-геттер с «текстовым» именем;
     * 3) текст в метаданных; 4) null → разбор будет повторён в текущем процессе.
     */
    private static String extractForkText(PipesForkResult result, Metadata meta) {
        // 1) известные имена
        for (String name : TEXT_GETTERS) {
            String value = invokeGetter(result, name, String.class);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // 2) автопоиск: String-геттер без параметров с «текстовым» именем
        String best = null;
        int bestScore = 0;
        for (Method method : result.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != String.class) {
                continue;
            }
            int score = textScore(method.getName());
            if (score <= bestScore || score == 0) {
                continue;
            }
            String value = invokeGetter(result, method.getName(), String.class);
            if (value != null && !value.isBlank()) {
                best = value;
                bestScore = score;
            }
        }
        if (best != null) {
            return best;
        }
        // 3) текст могли положить в метаданные
        if (meta != null) {
            for (String name : meta.names()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("content") || lower.contains("text") || lower.contains("body")) {
                    for (String value : meta.getValues(name)) {
                        if (value != null && value.length() >= 40) {   // не заголовок/титул
                            return value;
                        }
                    }
                }
            }
        }
        // 4) не смогли — логируем доступные методы (поможет захардкодить точный геттер)
        String methods = Arrays.stream(result.getClass().getMethods())
                .filter(m -> m.getParameterCount() == 0)
                .map(Method::getName)
                .distinct().sorted()
                .collect(Collectors.joining(", "));
        System.err.println("WARN: PipesForkResult без текстового геттера; доступные методы: ["
                + methods + "] — разбор повторён в текущем процессе.");
        return null;
    }

    /** Приоритет имени метода как источника текста: 4=лучший, 0=не текст. */
    private static int textScore(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("text")) return 4;
        if (n.contains("content")) return 3;
        if (n.contains("body")) return 2;
        if (n.contains("extract")) return 2;
        if (n.contains("output")) return 1;
        return 0;                                          // toString()/getClass() и пр. не берём
    }

    /** Безопасный вызов геттера: null, если метода нет или вернулся null. */
    private static <T> T invokeGetter(Object target, String name, Class<T> type) {
        try {
            Method method = target.getClass().getMethod(name);
            if (!type.isAssignableFrom(method.getReturnType())) {
                return null;
            }
            return type.cast(method.invoke(target));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Вызывает первый существующий сеттер(int|long) из списка кандидатов. */
    private static void applyFirstAvailable(PipesForkParserConfig config,
                                            List<String> setterNames, Number value) {
        for (String setterName : setterNames) {
            if (trySet(config, setterName, long.class, value.longValue())
                    || trySet(config, setterName, int.class, value.intValue())) {
                return;
            }
        }
        System.err.println("WARN: не найден сеттер " + setterNames.get(0) + "(...), " +
                "используется значение Tika по умолчанию.");
    }

    private static boolean trySet(Object target, String name, Class<?> type, Object value) {
        try {
            Method setter = target.getClass().getMethod(name, type);
            setter.invoke(target, value);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Вызывает первый найденный сеттер (String/int/enum) из списка кандидатов имён. */
    private static boolean trySetAny(Object target, List<String> setterNames, Object value) {
        for (Method method : target.getClass().getMethods()) {
            if (!setterNames.contains(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            Object arg = null;
            if (type == String.class) {
                arg = String.valueOf(value);
            } else if (type == int.class || type == Integer.class) {
                arg = Integer.valueOf(String.valueOf(value));
            } else if (type.isEnum() && value instanceof String text) {
                String normalized = text.replace('-', '_').toUpperCase(Locale.ROOT);
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(normalized)) {
                        arg = constant;
                        break;
                    }
                }
            }
            if (arg == null) {
                continue;
            }
            try {
                method.invoke(target, arg);
                return true;
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                // пробуем следующую перегрузку/имя
            }
        }
        return false;
    }

    private static String javaBin() {
        return System.getProperty("java.home")
                + System.getProperty("file.separator") + "bin"
                + System.getProperty("file.separator") + "java";
    }

    /* ────────────────────────── утилиты ────────────────────────── */

    private String detectType(byte[] data, String fileName) {
        Metadata md = new Metadata();
        md.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (TikaInputStream in = TikaInputStream.get(data)) {
            return detector.detect(in, md, new ParseContext()).toString();
        } catch (IOException e) {
            return MediaType.OCTET_STREAM.toString();
        }
    }

    private static boolean isWriteLimitReached(Throwable t) {
        while (t != null) {
            if (t instanceof WriteLimitReachedException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
    /**
     * Таймаут задачи форка: точное имя сеттера зависит от минорной версии Tika,
     * поэтому ищем любой числовой сеттер вида set*timeout* (приоритет — содержащим
     * «task»/«parse»), единицы определяем по имени («millis» → мс, иначе считаем секундами).
     */
    /**
     * Таймаут задачи форка. В Tika 4.x он вынесен в отдельный объект (TimeoutLimits),
     * который подключается через setTimeoutLimits(...). Тип объекта и имя внутреннего
     * сеттера определяем рефлексией: ищем числовой сеттер, содержащий «timeout»
     * (приоритет — «task»/«parse»; единицы — мс, если имя не содержит «second»).
     */
    private static void applyTaskTimeoutLimits(PipesForkParserConfig config, long millis) {
        // 1) метод setTimeoutLimits(...) и класс его параметра
        Method setter = null;
        for (Method method : config.getClass().getMethods()) {
            if (method.getName().equals("setTimeoutLimits") && method.getParameterCount() == 1) {
                setter = method;
                break;
            }
        }
        if (setter == null) {
            System.err.println("WARN: в PipesForkParserConfig нет setTimeoutLimits — таймаут задачи "
                    + "остаётся по умолчанию. Сеттеры: " + setterNames(config));
            return;
        }
        Class<?> limitsType = setter.getParameterTypes()[0];
        Object limits;
        try {
            limits = limitsType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            System.err.println("WARN: не удалось создать " + limitsType.getName()
                    + ": " + e.getMessage());
            return;
        }

        // 2) внутри объекта — числовой сеттер таймаута ЗАДАЧИ (не ожидания/завершения)
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method method : limitsType.getMethods()) {
            if (method.getParameterCount() != 1 || !method.getName().startsWith("set")) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            boolean numeric = type == long.class || type == Long.class
                    || type == int.class || type == Integer.class;
            if (!numeric && type != java.time.Duration.class) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("timeout")) {
                continue;
            }
            int score = 0;
            if (name.contains("task") || name.contains("parse")) {
                score += 3;
            }
            if (name.contains("millis")) {
                score += 1;
            }
            if (name.contains("wait") || name.contains("idle") || name.contains("shutdown")
                    || name.contains("exit") || name.contains("noresponse")
                    || name.contains("submit") || name.contains("emt")) {
                score -= 4;
            }
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null) {
            System.err.println("WARN: в " + limitsType.getSimpleName() + " нет сеттера таймаута задачи — "
                    + "используется значение Tika по умолчанию. Сеттеры: " + setterNames(limits));
            return;
        }

        // 3) единицы: мс, если только имя не указывает секунды
        String name = best.getName();
        boolean seconds = name.toLowerCase(Locale.ROOT).contains("second")
                && !name.toLowerCase(Locale.ROOT).contains("millis");
        long value = seconds ? Math.max(1, millis / 1000) : millis;
        try {
            Class<?> type = best.getParameterTypes()[0];
            Object arg;
            if (type == java.time.Duration.class) {
                arg = java.time.Duration.ofMillis(millis);
            } else if (type == int.class || type == Integer.class) {
                arg = Integer.valueOf((int) Math.min(value, Integer.MAX_VALUE));
            } else {
                arg = Long.valueOf(value);
            }
            best.invoke(limits, arg);
            setter.invoke(config, limits);
            System.out.println("INFO: таймаут задачи форка → " + limitsType.getSimpleName()
                    + "." + name + "(" + arg + ")");
        } catch (ReflectiveOperationException e) {
            System.err.println("WARN: не удалось применить " + name + ": " + e.getMessage());
        }
    }
    /** Имена всех сеттеров объекта — для диагностики, когда ожидаемый метод не найден. */
    private static String setterNames(Object target) {
        return java.util.Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().startsWith("set") && m.getParameterCount() == 1)
                .map(Method::getName)
                .distinct().sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }
}