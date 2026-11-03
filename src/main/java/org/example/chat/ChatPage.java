package org.example.chat;

/** HTML-страница простого web-чата для отправки вложений. */
public final class ChatPage {

    private ChatPage() { }

    public static final String HTML = """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <title>AI Tika Parser — Web Chat</title>
              <style>
                * { box-sizing: border-box; }
                body { margin:0; display:flex; flex-direction:column; height:100vh;
                       font-family:'Segoe UI',Arial,sans-serif; background:#1e1f22; color:#e3e3e3; }
                #chat { flex:1; overflow-y:auto; padding:16px; display:flex;
                        flex-direction:column; gap:10px; }
                .msg { max-width:85%; padding:10px 14px; border-radius:12px;
                       white-space:pre-wrap; word-break:break-word; }
                .user { align-self:flex-end; background:#2563eb; color:#fff; }
                .bot  { align-self:flex-start; background:#2b2d31; border:1px solid #3a3d42;
                        font-family:Consolas,'Courier New',monospace; font-size:13px; }
                form { display:flex; gap:8px; padding:12px; background:#2b2d31;
                       border-top:1px solid #3a3d42; align-items:center; flex-wrap:wrap; }
                #msg { flex:1; min-width:200px; padding:10px; border-radius:8px;
                       border:1px solid #3a3d42; background:#1e1f22; color:#fff; }
                #ocrLang { width:120px; padding:9px; border-radius:8px;
                           border:1px solid #3a3d42; background:#1e1f22; color:#fff; }
                label { display:flex; gap:6px; align-items:center; font-size:14px;
                        color:#b8b9bd; cursor:pointer; user-select:none; }
                button { padding:10px 18px; border:none; border-radius:8px;
                         background:#2563eb; color:#fff; cursor:pointer; }
              </style>
            </head>
            <body>
              <div id="chat">
                <div class="msg bot">Привет! Прикрепите файлы (docx, pdf, xlsx, pptx, txt, html,
            сканы png/jpg и т.д.) и нажмите «Отправить» — я разберу их через Apache Tika.
            Для сканов и картинок включён OCR (rus+chi_sim). Снимите галочку «Метаданные»,
            чтобы получать только текст. «Скан PDF» — для PDF без текстового слоя.</div>
              </div>
              <form id="form">
                <input id="msg" type="text" placeholder="Сообщение (необязательно)">
                <label><input type="checkbox" id="meta" checked> Метаданные</label>
                <label><input type="checkbox" id="ocr" checked> OCR</label>
                <input id="ocrLang" type="text" value="rus+chi_sim" title="Языки OCR: rus+chi_sim+eng, chi_tra...">
                <label><input type="checkbox" id="ocrScan"> Скан PDF</label>
                <input id="files" type="file" multiple required>
                <button type="submit">Отправить</button>
              </form>
              <script>
                const chat = document.getElementById('chat');
                document.getElementById('form').onsubmit = async (e) => {
                  e.preventDefault();
                  const files = document.getElementById('files').files;
                  if (!files.length) return;
                  const fd = new FormData();
                  fd.append('message', document.getElementById('msg').value);
                  fd.append('meta', document.getElementById('meta').checked);
                  if (!document.getElementById('ocr').checked) fd.append('ocr', 'false');
                  const ocrLang = document.getElementById('ocrLang').value.trim();
                  if (ocrLang) fd.append('ocrLang', ocrLang);
                  if (document.getElementById('ocrScan').checked) fd.append('ocrScan', 'true');
                  for (const f of files) fd.append('files', f, f.name);
                  addUser([...files].map(f => '[вложение] ' + f.name).join('\\n'));
                  const bot = addBot('Разбираю вложения... (OCR может занять до минуты)');
                  try {
                    const r = await fetch('/api/chat/message', { method:'POST', body: fd });
                    bot.textContent = await r.text();
                  } catch (err) {
                    bot.textContent = 'Ошибка запроса: ' + err;
                  }
                  chat.scrollTop = chat.scrollHeight;
                };
                function addUser(t) { const d = document.createElement('div');
                  d.className='msg user'; d.textContent=t; chat.appendChild(d);
                  chat.scrollTop = chat.scrollHeight; }
                function addBot(t) { const d = document.createElement('div');
                  d.className='msg bot'; d.textContent=t; chat.appendChild(d);
                  chat.scrollTop = chat.scrollHeight; return d; }
              </script>
            </body>
            </html>
            """;
}