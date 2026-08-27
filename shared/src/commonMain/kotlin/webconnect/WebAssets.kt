package net.morsecode.webconnect

/**
 * The hand-written static frontend for Web Connect (Section H.3).
 *
 * Plain HTML/CSS/JS, deliberately NOT Compose-for-Web. Embedded as Kotlin
 * strings so the server is fully self-contained in `commonMain` without needing
 * classpath-resource access (which would require JVM APIs unavailable in
 * common). The same markup ships under `resources/webapp/` for reference.
 *
 * Three views, as specified: Pair (PIN entry), Files (drag-drop upload + shared
 * list), Chat (WebSocket).
 */
object WebAssets {

    val indexHtml = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Morse Code — Web Connect</title><link rel="stylesheet" href="/style.css"></head>
<body>
<main>
  <h1>Morse Code</h1>
  <section id="pair">
    <p>Enter the 6-digit PIN shown on the device, or scan its QR code.</p>
    <input id="pin" inputmode="numeric" maxlength="6" placeholder="PIN">
    <button id="pairBtn">Connect</button>
    <p id="pairErr" class="err"></p>
  </section>
  <section id="app" hidden>
    <nav><button data-view="files">Files</button><button data-view="chat">Chat</button></nav>
    <div id="files">
      <div id="drop">Drop a file here to send it to the device</div>
      <input type="file" id="file"><button id="upBtn">Upload</button>
      <h2>Shared by the device</h2><ul id="shared"></ul>
    </div>
    <div id="chat" hidden>
      <ul id="msgs"></ul>
      <input id="msg" placeholder="Message"><button id="sendBtn">Send</button>
    </div>
  </section>
</main>
<script src="/app.js"></script>
</body></html>
""".trimIndent()

    val styleCss = """
body{font-family:system-ui,sans-serif;background:#f4f5fb;color:#17146b;margin:0}
main{max-width:560px;margin:0 auto;padding:24px}
h1{color:#4f46e5}
section{background:#fff;border-radius:12px;padding:16px;margin-top:16px;box-shadow:0 1px 4px rgba(0,0,0,.1)}
input,button{padding:10px;border-radius:8px;border:1px solid #ccc;font-size:16px}
button{background:#4f46e5;color:#fff;border:none;cursor:pointer}
#drop{border:2px dashed #4f46e5;border-radius:12px;padding:24px;text-align:center;margin:8px 0}
.err{color:#ba1a1a}
#msgs{list-style:none;padding:0;max-height:300px;overflow:auto}
#msgs li{margin:6px 0;padding:8px;border-radius:8px;background:#eef}
""".trimIndent()

    // Deliberately no template literals so this is safe to embed in a Kotlin string.
    val appJs = """
function el(id){return document.getElementById(id)}
var ws=null;
function showApp(){el('pair').hidden=true;el('app').hidden=false;loadShared();openChat();}
el('pairBtn').onclick=function(){
  fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({pin:el('pin').value})}).then(function(r){
    if(r.ok){showApp()}else{el('pairErr').textContent='Wrong PIN';}
  });
};
function loadShared(){
  fetch('/api/shared-files').then(function(r){return r.json()}).then(function(list){
    var ul=el('shared');ul.innerHTML='';
    (list||[]).forEach(function(f){
      var li=document.createElement('li');var a=document.createElement('a');
      a.href='/api/download/'+f.id;a.textContent=f.name;li.appendChild(a);ul.appendChild(li);
    });
  }).catch(function(){});
}
function upload(file){
  var fd=new FormData();fd.append('file',file);
  fetch('/api/upload',{method:'POST',body:fd}).then(loadShared);
}
el('upBtn').onclick=function(){if(el('file').files[0])upload(el('file').files[0]);};
el('drop').ondragover=function(e){e.preventDefault();};
el('drop').ondrop=function(e){e.preventDefault();if(e.dataTransfer.files[0])upload(e.dataTransfer.files[0]);};
document.querySelectorAll('nav button').forEach(function(b){
  b.onclick=function(){
    el('files').hidden=b.dataset.view!=='files';
    el('chat').hidden=b.dataset.view!=='chat';
  };
});
function openChat(){
  if(ws)return;
  ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws/chat');
  ws.onmessage=function(e){var li=document.createElement('li');li.textContent=e.data;el('msgs').appendChild(li);};
}
el('sendBtn').onclick=function(){
  if(ws&&el('msg').value){ws.send(el('msg').value);el('msg').value='';}
};
fetch('/api/shared-files').then(function(r){if(r.ok)showApp();}).catch(function(){});
""".trimIndent()
}
