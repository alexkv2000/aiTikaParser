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

    private static final String ENV_TESSERACT_PATH = "TESSERACT_PATH";
    private static final String ENV_TESSDATA_PATH = "TESSDATA_PATH";
    private static final String ENV_PATH = "PATH";
    private static final String WINDOWS_BINARY = "tesseract.exe";
    private static final String UNIX_BINARY = "tesseract";
    private static final String TESSDATA_DIR_NAME = "tessdata";
    private static final String TRAINEDDATA_SUFFIX = ".traineddata";
    private static final List<String> WINDOWS_INSTALL_DIRS = List.of(
            "C:\\Program Files\\Tesseract-OCR",
            "C:\\Program Files (x86)\\Tesseract-OCR");

    private OcrEnv() { }

    /** Путь к tesseract: env TESSERACT_PATH (файл ИЛИ каталог) → стандартные каталоги → PATH. */
    public static Optional<Path> tesseractBinary() {
        Optional<Path> fromEnv = fromEnvOverride();
        if (fromEnv.isPresent()) {
            return fromEnv;
        }
        Optional<Path> fromDefault = fromWindowsInstallDirs();
        return fromDefault.isPresent() ? fromDefault : fromPathEnv();
    }

    /** env TESSERACT_PATH: путь к файлу бинарника ИЛИ к каталогу с ним. */
    private static Optional<Path> fromEnvOverride() {
        String env = System.getenv(ENV_TESSERACT_PATH);
        if (env == null || env.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(env.strip());
        return Files.isRegularFile(path) ? Optional.of(path) : binaryIn(path);
    }

    /** Стандартные каталоги установки Tesseract в Windows. */
    private static Optional<Path> fromWindowsInstallDirs() {
        if (!isWindows()) {
            return Optional.empty();
        }
        for (String dir : WINDOWS_INSTALL_DIRS) {
            Optional<Path> exe = binaryIn(Path.of(dir));
            if (exe.isPresent()) {
                return exe;
            }
        }
        return Optional.empty();
    }

    /** Поиск бинарника в каталогах из переменной PATH. */
    private static Optional<Path> fromPathEnv() {
        String pathEnv = System.getenv(ENV_PATH);
        if (pathEnv == null) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Optional<Path> exe = binaryIn(Path.of(dir.replace("\"", "").strip()));
            if (exe.isPresent()) {
                return exe;
            }
        }
        return Optional.empty();
    }

    /** Бинарник tesseract внутри каталога (если существует). */
    private static Optional<Path> binaryIn(Path dir) {
        Path exe = dir.resolve(binaryName());
        return Files.isRegularFile(exe) ? Optional.of(exe) : Optional.empty();
    }

    private static String binaryName() {
        return isWindows() ? WINDOWS_BINARY : UNIX_BINARY;
    }

    /** Каталог tessdata: env TESSDATA_PATH → <каталог бинарника>\tessdata. */
    public static Optional<Path> tessdataDir() {
        String env = System.getenv(ENV_TESSDATA_PATH);
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.strip());
            if (Files.isDirectory(p)) {
                return Optional.of(p);
            }
        }
        return tesseractBinary().map(Path::getParent)
                .map(dir -> dir.resolve(TESSDATA_DIR_NAME))
                .filter(Files::isDirectory);
    }

    /** Отсутствующие traineddata для строки языков вида «rus+chi_sim+eng». */
    public static List<String> missingLanguages(String ocrLanguages) {
        Optional<Path> tessdata = tessdataDir();
        if (tessdata.isEmpty()) {
            return List.of(ocrLanguages);
        }
        List<String> missing = new ArrayList<>();
        for (String lang : ocrLanguages.split("\\+")) {
            String name = lang.strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()
                    && !Files.isRegularFile(tessdata.get().resolve(name + TRAINEDDATA_SUFFIX))) {
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
        List<String> missing = missingLanguages("rus+eng");
        if (missing.isEmpty()) {
            return "found (" + bin.get().getParent() + "; rus+eng ok)";
        }
        return "found (" + bin.get().getParent() + "; НЕТ "
                + String.join(",", missing) + TRAINEDDATA_SUFFIX + ")";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}