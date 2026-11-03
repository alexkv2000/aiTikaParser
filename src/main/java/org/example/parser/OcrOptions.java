package org.example.parser;

/** Настройки OCR (Tesseract) для одного запроса. */
public record OcrOptions(boolean enabled, String language, boolean scanPdfPages) {

    /** OCR включён; языки «русский + упрощённый китайский»; постраничный рендер PDF выключен. */
    public static OcrOptions defaults() {
        return new OcrOptions(true, "rus+eng", false);
    }
}