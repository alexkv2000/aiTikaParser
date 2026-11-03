ЗАПУСК
set MAX_FORKS=8 - кол-во одновременных файлов в обработке
java -jar target\ai_tika_parser-1.0-SNAPSHOT.jar

$env:MAX_FORKS = "8";
java -jar target\ai_tika_parser-1.0-SNAPSHOT.jar

OCR — СКАНЫ И КАРТИНКИ (русский + китайский), вкл. встроенные в doc/docx/pdf
  1) Установите Tesseract: https://github.com/UB-Mannheim/tesseract/wiki
     при установке отметьте языки Russian и Chinese (Simplified).
  2) Добавьте в PATH: C:\Program Files\Tesseract-OCR
     (если PATH не виден серверу — задайте env TESSERACT_PATH и TESSDATA_PATH)
  3) Проверка: tesseract --list-langs   → должны быть rus и chi_sim
  4) Проверка в сервисе: GET http://localhost:8081/health → "OK; tesseract=found"

  Параметры запроса:
    ?ocr=false             выключить OCR (по умолчанию включён)
    ?ocrLang=rus+chi_sim   языки: rus+chi_sim+eng, chi_tra (традиционный кит.) и т.п.
                           (в адресной строке браузера пишите rus%2Bchi_sim;
                            в Postman/веб-чате можно rus+chi_sim)
    ?ocrScan=true          постраничный OCR сканированных PDF (медленно, только для сканов)

PostMan
    POST  http://localhost:8081/api/chat/message?meta=false
    Вкладка Body → выбрать form-data

    на поле Key — «Text» / «File»:
    Key         Тип  Value                               Обязателен
    message     Text Разбери вложения                    нет
    files       File выбрать файл (Select Files)         да
    files       File ещё файл (для нескольких вложений)  —
    meta        Text false                               нет (по умолчанию true)
    ocr         Text true|false                          нет (по умолчанию true)
    ocrLang     Text rus+chi_sim                         нет (по умолчанию rus+chi_sim)
    ocrScan     Text true                                нет (по умолчанию false)

    Параметры можно передавать и в URL: ?meta=false&ocr=true&ocrLang=rus%2Bchi_sim

    Примеры (curl):
      скан-картинка рус+кит:
        curl -s -F "files=@dogovor_scan.png" http://localhost:8081/api/chat/message
      сканированный PDF (постраничный OCR):
        curl -s -F "files=@contract_scan.pdf" "http://localhost:8081/api/chat/message?ocrScan=true"
      docx со вставленными скриншотами (OCR встроенных картинок — автоматически):
        curl -s -F "files=@otchet.docx" http://localhost:8081/api/chat/message