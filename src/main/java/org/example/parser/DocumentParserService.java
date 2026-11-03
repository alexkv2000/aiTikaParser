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
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Разбор документов.
 * Маршрутизация:
 *  - OCR включён  → разбор в ТЕКУЩЕМ процессе (Tika 4 сериализует ParseContext в JSON,
 *    и конфиги OCR/PDF в дочернюю JVM не передаются).
 *  - OCR выключен → PipesForkParser (изоляция сбоев); если форк не смог вернуть
 *    текст или упал — автоматический повтор в текущем процессе.
 * Базовые настройки обоих процессов — tika-config.xml в jar.
 */
public final class DocumentParserService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DocumentParserService.class.getName());

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

    /** Слова-маркеры «не той» природы таймаута (ожидание/завершение, а не сама задача). */
    private static final List<String> NON_TASK_TIMEOUT_MARKERS =
            List.of("wait", "idle", "shutdown", "exit", "noresponse", "submit", "emt");

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
            LOG.warning("setMaxFilesPerProcess не найден — дочерние процессы не перезапускаются.");
        }
        this.forkParser = new PipesForkParser(config);
    }

    public int defaultWriteLimit() {
        return defaultWriteLimit;
    }

    /** Старая сигнатура: с настройками OCR по умолчанию (вкл., rus+chi_sim+eng). */
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
     * Разбор в дочерней JVM. ParseContext — ПУСТОЙ: Tika 4 сериализует контекст в JSON,
     * и незарегистрированные конфиги (PDFParserConfig и др.) рушат сериализацию.
     * Если текст достать не удалось — возвращаем «ошибку», разбор повторится локально.
     */
    private Attempt parseWithFork(byte[] data, String fileName, int limit) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        try (TikaInputStream in = TikaInputStream.get(data)) {
            PipesForkResult result = forkParser.parse(in, metadata, new ParseContext());

            // исключение, пришедшее из дочерней JVM
            Throwable childError = invokeGetter(result, "getException", Throwable.class);
            if (childError != null) {
                return failure(metadata, childError);
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
            return failure(metadata, e);
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
            return failure(metadata, e);
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

        tess.setLanguage(ocr.language());                 // «rus+chi_sim+eng», «rus+chi_sim+eng», «chi_tra»…

        // Пути из env (сеттеры в Tika 4.x проверяют существование пути и бросают TikaConfigException)
        OcrEnv.tesseractBinary().ifPresent(bin -> {
            try {
                tess.setTesseractPath(bin.toString());
            } catch (TikaConfigException e) {
                LOG.warning(() -> "tesseractPath (" + bin + ") отклонён: " + e.getMessage());
            }
        });
        OcrEnv.tessdataDir().ifPresent(dir -> {
            try {
                tess.setTessdataPath(dir.toString());
            } catch (TikaConfigException e) {
                LOG.warning(() -> "tessdataPath (" + dir + ") отклонён: " + e.getMessage());
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
                LOG.warning("постраничный рендер PDF недоступен в этой версии Tika; "
                        + "сканы PDF обрабатываются через OCR встроенных картинок.");
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
            restoreInterruptFlag(e);
            LOG.warning(() -> "ошибка остановки fork-парсера: " + e.getMessage());
        }
    }

    /* ─────────── версионо-зависимые вызовы API Tika 4.x ─────────── */

    /** Текст из PipesForkResult: известные геттеры → автопоиск → метаданные → null. */
    private static String extractForkText(PipesForkResult result, Metadata meta) {
        String text = firstNonBlankGetter(result);
        if (text == null) {
            text = bestTextGetter(result);
        }
        if (text == null) {
            text = textFromMetadata(meta);
        }
        if (text == null) {
            LOG.warning(() -> "PipesForkResult без текстового геттера; доступные методы: ["
                    + noArgMethodNames(result) + "] — разбор повторён в текущем процессе.");
        }
        return text;
    }

    /** 1) известные имена текстовых геттеров. */
    private static String firstNonBlankGetter(PipesForkResult result) {
        return TEXT_GETTERS.stream()
                .map(name -> invokeGetter(result, name, String.class))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** 2) автопоиск: String-геттер без параметров с «текстовым» именем (по убыванию приоритета). */
    private static String bestTextGetter(PipesForkResult result) {
        return Arrays.stream(result.getClass().getMethods())
                .filter(m -> m.getParameterCount() == 0 && m.getReturnType() == String.class)
                .filter(m -> textScore(m.getName()) > 0)
                .sorted(Comparator.comparingInt((Method m) -> textScore(m.getName())).reversed())
                .map(m -> invokeGetter(result, m.getName(), String.class))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** 3) текст могли положить в метаданные. */
    private static String textFromMetadata(Metadata meta) {
        if (meta == null) {
            return null;
        }
        return Arrays.stream(meta.names())
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.contains("content") || lower.contains("text")
                            || lower.contains("body");
                })
                .flatMap(name -> Arrays.stream(meta.getValues(name)))
                .filter(value -> value != null && value.length() >= 40)   // не заголовок/титул
                .findFirst()
                .orElse(null);
    }

    /** Приоритет имени метода как источника текста: 4=лучший, 0=не текст. */
    private static int textScore(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("text")) {
            return 4;
        }
        if (n.contains("content")) {
            return 3;
        }
        if (n.contains("body") || n.contains("extract")) {
            return 2;
        }
        if (n.contains("output")) {
            return 1;
        }
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
        boolean applied = setterNames.stream()
                .anyMatch(name -> trySet(config, name, long.class, value.longValue())
                        || trySet(config, name, int.class, value.intValue()));
        if (!applied) {
            LOG.warning(() -> "не найден сеттер " + setterNames.get(0) + "(...), "
                    + "используется значение Tika по умолчанию.");
        }
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

    /** Вызывает первый подходящий сеттер (String/int/enum) из списка кандидатов имён. */
    private static boolean trySetAny(Object target, List<String> setterNames, Object value) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> setterNames.contains(m.getName()) && m.getParameterCount() == 1)
                .anyMatch(m -> tryInvoke(m, target, coerceArg(m.getParameterTypes()[0], value)));
    }

    /** Приводит значение к типу параметра сеттера; null — если тип не поддерживается. */
    private static Object coerceArg(Class<?> type, Object value) {
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(String.valueOf(value));
        }
        if (type.isEnum() && value instanceof String text) {
            return enumConstant(type, text);
        }
        return null;
    }

    private static Object enumConstant(Class<?> enumType, String name) {
        String normalized = name.replace('-', '_').toUpperCase(Locale.ROOT);
        return Arrays.stream(enumType.getEnumConstants())
                .filter(c -> ((Enum<?>) c).name().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private static boolean tryInvoke(Method method, Object target, Object arg) {
        if (arg == null) {
            return false;
        }
        try {
            method.invoke(target, arg);                    // пробуем следующую перегрузку/имя при сбое
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Таймаут задачи форка. В Tika 4.x он вынесен в отдельный объект (TimeoutLimits),
     * подключаемый через setTimeoutLimits(...). Тип объекта и имя внутреннего сеттера
     * определяем рефлексией: числовой сеттер, содержащий «timeout» (приоритет — «task»/«parse»),
     * единицы — мс, если имя не содержит «second».
     */
    private static void applyTaskTimeoutLimits(PipesForkParserConfig config, long millis) {
        Optional<Method> setter = timeoutLimitsSetter(config);
        if (setter.isEmpty()) {
            LOG.warning(() -> "в PipesForkParserConfig нет setTimeoutLimits — таймаут задачи "
                    + "остаётся по умолчанию. Сеттеры: " + setterNames(config));
            return;
        }
        Class<?> limitsType = setter.get().getParameterTypes()[0];
        Object limits;
        try {
            limits = limitsType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            LOG.warning(() -> "не удалось создать " + limitsType.getName() + ": " + e.getMessage());
            return;
        }
        Optional<Method> taskTimeout = taskTimeoutSetter(limitsType);
        if (taskTimeout.isEmpty()) {
            LOG.warning(() -> "в " + limitsType.getSimpleName() + " нет сеттера таймаута задачи — "
                    + "используется значение Tika по умолчанию. Сеттеры: " + setterNames(limits));
            return;
        }
        Method apply = taskTimeout.get();
        Object arg = timeoutArg(apply, millis);
        try {
            apply.invoke(limits, arg);
            setter.get().invoke(config, limits);
            LOG.info(() -> "таймаут задачи форка → " + limitsType.getSimpleName()
                    + "." + apply.getName() + "(" + arg + ")");
        } catch (ReflectiveOperationException e) {
            LOG.warning(() -> "не удалось применить " + apply.getName() + ": " + e.getMessage());
        }
    }

    /** Метод setTimeoutLimits(...) с единственным параметром. */
    private static Optional<Method> timeoutLimitsSetter(PipesForkParserConfig config) {
        return Arrays.stream(config.getClass().getMethods())
                .filter(m -> m.getName().equals("setTimeoutLimits") && m.getParameterCount() == 1)
                .findFirst();
    }

    /** Числовой сеттер таймаута ЗАДАЧИ (не ожидания/завершения) с максимальным приоритетом. */
    private static Optional<Method> taskTimeoutSetter(Class<?> limitsType) {
        return Arrays.stream(limitsType.getMethods())
                .filter(m -> m.getParameterCount() == 1)
                .filter(m -> m.getName().startsWith("set"))
                .filter(DocumentParserService::isTimeoutSetter)
                .max(Comparator.comparingInt(m -> taskTimeoutScore(m.getName())));
    }

    /** Сеттер с числовым или Duration-параметром и «timeout» в имени. */
    private static boolean isTimeoutSetter(Method method) {
        Class<?> type = method.getParameterTypes()[0];
        boolean numeric = type == long.class || type == Long.class
                || type == int.class || type == Integer.class;
        boolean timeout = method.getName().toLowerCase(Locale.ROOT).contains("timeout");
        return timeout && (numeric || type == Duration.class);
    }

    /** Приоритет сеттера таймаута: +3 «task»/«parse», +1 «millis», −4 «wait»/«idle»/… */
    private static int taskTimeoutScore(String setterName) {
        String name = setterName.toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.contains("task") || name.contains("parse")) {
            score += 3;
        }
        if (name.contains("millis")) {
            score += 1;
        }
        if (NON_TASK_TIMEOUT_MARKERS.stream().anyMatch(name::contains)) {
            score -= 4;
        }
        return score;
    }

    /** Аргумент сеттера: Duration, int или long; мс или секунды (по имени метода). */
    private static Object timeoutArg(Method setter, long millis) {
        String name = setter.getName().toLowerCase(Locale.ROOT);
        boolean seconds = name.contains("second") && !name.contains("millis");
        long value = seconds ? Math.max(1, millis / 1000) : millis;
        Class<?> type = setter.getParameterTypes()[0];
        if (type == Duration.class) {
            return Duration.ofMillis(millis);
        }
        if (type == int.class || type == Integer.class) {
            return (int) Math.min(value, Integer.MAX_VALUE);
        }
        return value;
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

    /** Попытка-ошибка; если разбор был прерван — восстанавливаем флаг прерывания потока. */
    private static Attempt failure(Metadata metadata, Throwable e) {
        restoreInterruptFlag(e);
        return new Attempt(metadata, "", false, describe(e));
    }

    /** InterruptedException в цепочке причин → восстановить флаг прерывания потока. */
    private static void restoreInterruptFlag(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            t = t.getCause();
        }
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

    /** Имена всех сеттеров объекта — для диагностики, когда ожидаемый метод не найден. */
    private static String setterNames(Object target) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().startsWith("set") && m.getParameterCount() == 1)
                .map(Method::getName)
                .distinct().sorted()
                .collect(Collectors.joining(", "));
    }

    /** Имена всех методов без параметров — диагностика доступного API PipesForkResult. */
    private static String noArgMethodNames(Object target) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getParameterCount() == 0)
                .map(Method::getName)
                .distinct().sorted()
                .collect(Collectors.joining(", "));
    }
}