package org.example.parser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Поиск Tesseract и проверка языковых файлов (диагностика для баннера и /health). */
public final class OcrEnv {

    private OcrEnv() { }

    /** Путь к tesseract: env TESSERACT_PATH (файл ИЛИ каталог) → стандартные каталоги → PATH. */
    public static Optional<Path> tesseractBinary() {
        String env = System.getenv("TESSERACT_PATH");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.strip());
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
            if (Files.isDirectory(p)) {
                Path exe = p.resolve(isWindows() ? "tesseract.exe" : "tesseract");
                if (Files.isRegularFile(exe)) {
                    return Optional.of(exe);
                }
            }
        }
        if (isWindows()) {
            for (String dir : List.of("C:\\Program Files\\Tesseract-OCR",
                    "C:\\Program Files (x86)\\Tesseract-OCR")) {
                Path exe = Path.of(dir, "tesseract.exe");
                if (Files.isRegularFile(exe)) {
                    return Optional.of(exe);
                }
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String exeName = isWindows() ? "tesseract.exe" : "tesseract";
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path exe = Path.of(dir.replace("\"", "").strip(), exeName);
                if (Files.isRegularFile(exe)) {
                    return Optional.of(exe);
                }
            }
        }
        return Optional.empty();
    }

    /** Каталог tessdata: env TESSDATA_PATH → <каталог бинарника>\tessdata. */
    public static Optional<Path> tessdataDir() {
        String env = System.getenv("TESSDATA_PATH");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.strip());
            if (Files.isDirectory(p)) {
                return Optional.of(p);
            }
        }
        return tesseractBinary().map(Path::getParent)
                .map(dir -> dir.resolve("tessdata"))
                .filter(Files::isDirectory);
    }

    /** Отсутствующие traineddata для строки языков вида «rus+chi_sim». */
    public static List<String> missingLanguages(String ocrLanguages) {
        Optional<Path> tessdata = tessdataDir();
        if (tessdata.isEmpty()) {
            return List.of(ocrLanguages);
        }
        List<String> missing = new ArrayList<>();
        for (String lang : ocrLanguages.split("\\+")) {
            String name = lang.strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()
                    && !Files.isRegularFile(tessdata.get().resolve(name + ".traineddata"))) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** Человекочитаемый статус для /health и баннера. */
    public static String status() {
        Optional<Path> bin = tesseractBinary();
        if (bin.isEmpty()) {
            return "NOT FOUND (задайте PATH или env TESSERACT_PATH/TESSDATA_PATH)";
        }
        List<String> missing = missingLanguages("rus+chi_sim");
        if (missing.isEmpty()) {
            return "found (" + bin.get().getParent() + "; rus+chi_sim ok)";
        }
        return "found (" + bin.get().getParent() + "; НЕТ "
                + String.join(",", missing) + ".traineddata)";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}