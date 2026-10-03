"""iris web UI: python ui.py  ->  http://localhost:8765"""
import config  # noqa: F401
import os, updater, secrets, hmac
import json, threading, platform, webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from events import LOGS, STATE, LOCK
from memory import KnowledgeBase

PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>iris</title>
<style>
:root{--bg:#0b1a2b;--card:#0f2740;--line:#2f9fd8;--tx:#e6f1fb;--mut:#8fb3cf}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--tx);font:15px system-ui;padding:16px;max-width:900px;margin:auto}
h1{font-size:20px}h1 span{color:var(--line)}h2{font-size:14px;color:var(--line);margin:0 0 8px;text-transform:uppercase;letter-spacing:.05em}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;margin:12px 0}
textarea,input{width:100%;background:#0b1a2b;color:var(--tx);border:1px solid #28506f;border-radius:8px;padding:10px;font:inherit}
button{background:var(--line);color:#04121f;border:0;border-radius:8px;padding:10px 16px;font-weight:600;cursor:pointer}
button:disabled{opacity:.5}label{margin-right:12px;color:var(--mut)}
pre{white-space:pre-wrap;word-break:break-word;max-height:340px;overflow:auto;margin:0;font:12px ui-monospace,monospace;color:#b9d6ee}
.pill{display:inline-block;padding:2px 8px;border-radius:99px;font-size:12px;margin-right:6px}
.pass{background:#12452d}.fail{background:#5a1e1e}.skipped{background:#4a4220}.run{background:#134763}
.mem{border-top:1px solid #1d3b57;padding:8px 0}.mem small{color:var(--mut)}
</style>
<h1><span>iris</span> · app development for iPhone and Mac</h1>
<div class=card><h2>Task</h2>
<textarea id=task rows=3 placeholder="e.g. A habit tracker with streaks and daily reminders"></textarea>
<p><label><input type=checkbox id=ios checked style="width:auto"> iPhone</label>
<label><input type=checkbox id=macos checked style="width:auto"> Mac</label>
<label><input type=checkbox id=android style="width:auto"> Android</label>
<label>Max fix loops <input id=iters type=number value=4 min=1 max=10 style="width:70px"></label></p>
<button id=go onclick=run()>Build it</button> <span id=status class="pill run">idle</span></div>
<div class=card><h2>Updates</h2><div id=upd>-</div><p>
<button onclick="post('/api/update/now')">Update now</button>
<button onclick="post('/api/update/rollback')" style="background:#28506f;color:#e6f1fb">Rollback</button>
<button id=rs onclick="post('/api/restart')" hidden>Restart to apply</button></p></div>
<div class=card><h2>Results</h2><div id=res>-</div><small id=wsp></small></div>
<div class=card><h2>Live log</h2><pre id=log></pre></div>
<div class=card><h2>Shared memory</h2><input id=q placeholder="search knowledge..." oninput=mem()><div id=mem></div></div>
<script>
const TOKEN='__TOKEN__';
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function run(){const p=[];if(ios.checked)p.push('ios');if(macos.checked)p.push('macos');if(android.checked)p.push('android');
await fetch('/api/run',{method:'POST',headers:{'X-Token':TOKEN},body:JSON.stringify({task:task.value,platforms:p,max_iters:+iters.value})});}
async function post(u){await fetch(u,{method:'POST',headers:{'X-Token':TOKEN},body:'{}'});}
async function upd_(){const s=await (await fetch('/api/update')).json();
document.getElementById('rs').hidden=!s.restart_needed;
document.getElementById('upd').innerHTML=`<small>v${esc(s.version)} · model ${esc(s.model)} · auto-update: ${esc(s.mode)}${s.update_url_set?'':' · UPDATE_URL not set'}${s.update_available?' · <b>v'+esc(s.update_available)+' available</b>':''}</small><br><small>${s.messages.map(esc).join('<br>')}</small>`;}
async function poll(){upd_();const s=await (await fetch('/api/state')).json();
status.textContent=s.status;go.disabled=s.status==='running';
const l=document.getElementById('log'),b=l.scrollTop+l.clientHeight>=l.scrollHeight-20;l.textContent=s.logs.join('\\n');if(b)l.scrollTop=l.scrollHeight;
wsp.textContent=s.workspace?('Project folder: '+s.workspace):'';res.innerHTML=s.results?Object.entries(s.results).map(([p,r])=>`<span class="pill ${esc(r.status)}">${esc(p)}: ${esc(r.status)} (${esc(r.iters)} loop${r.iters>1?'s':''})</span>`).join(''):'-';}
async function mem(){const m=await (await fetch('/api/memory?q='+encodeURIComponent(q.value))).json();
mem_.innerHTML=m.map(r=>`<div class=mem><b>${esc(r.title)}</b> <span class=pill>${esc(r.kind)}</span><span class=pill>${esc(r.status)}</span><br>
<small>${esc(r.source)} · ${esc(r.date)} · confidence ${esc(r.confidence)}</small><br>${esc(r.content.slice(0,200))}</div>`).join('')||'<small>empty</small>';}
const mem_=document.getElementById('mem');setInterval(poll,1500);setInterval(()=>{if(!q.value)mem()},6000);poll();mem();
</script>"""


TOKEN = secrets.token_urlsafe(24)                       # blocks other websites from driving the local server
HOSTS = {"localhost:8765", "127.0.0.1:8765"}              # blocks DNS-rebinding


class H(BaseHTTPRequestHandler):
    kb = KnowledgeBase()

    def _send(self, body, ctype="application/json", code=200):
        b = body.encode()
        self.send_response(code); self.send_header("Content-Type", ctype)
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; connect-src 'self'")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.headers.get("Host", "") not in HOSTS:
            return self._send("{}", code=403)
        u = urlparse(self.path)
        if u.path == "/":
            page = PAGE
            if platform.system() != "Darwin":                  # Apple builds need a Mac
                page = page.replace("id=ios checked", "id=ios").replace("id=macos checked", "id=macos").replace("id=android style", "id=android checked style")
            return self._send(page.replace("__TOKEN__", TOKEN), "text/html")
        if u.path == "/api/update":
            return self._send(json.dumps(updater.status()))
        if u.path == "/api/state":
            with LOCK:
                return self._send(json.dumps({**STATE, "logs": LOGS[-400:]}))
        if u.path == "/api/memory":
            q = parse_qs(u.query).get("q", [""])[0]
            keys = "id kind title content source date version evidence confidence status".split()
            return self._send(json.dumps([dict(zip(keys, r)) for r in self.kb.items(q, 20)]))
        self._send("{}", code=404)

    def do_POST(self):
        if self.headers.get("Host", "") not in HOSTS or not hmac.compare_digest(self.headers.get("X-Token", ""), TOKEN):
            return self._send("{}", code=403)
        if self.path == "/api/update/now":
            threading.Thread(target=lambda: updater.run_update(force=True), daemon=True).start()
            return self._send('{"ok": true}')
        if self.path == "/api/update/rollback":
            threading.Thread(target=updater.rollback, daemon=True).start()
            return self._send('{"ok": true}')
        if self.path == "/api/restart":
            self._send('{"ok": true}')
            threading.Timer(0.5, lambda: os._exit(3)).start()     # start.command restarts on exit code 3
            return
        if self.path != "/api/run":
            return self._send("{}", code=404)
        from orchestrator import Orchestrator, PLATFORMS
        try:
            n = int(self.headers.get("Content-Length", 0))
            d = json.loads(self.rfile.read(min(n, 100_000)) or "{}")
            task = str(d.get("task", "")).strip()[:4000]
            plats = [p for p in d.get("platforms", []) if p in PLATFORMS]
            iters = max(1, min(int(d.get("max_iters", 4)), 10))
        except (ValueError, TypeError, AttributeError):
            return self._send(json.dumps({"ok": False}), code=400)
        if STATE["status"] == "running" or not task or not plats or len(plats) != len(d.get("platforms", [])):
            return self._send(json.dumps({"ok": False}), code=400)
        LOGS.clear()
        threading.Thread(target=lambda: Orchestrator(platforms=plats, max_iters=iters).run(task), daemon=True).start()
        self._send(json.dumps({"ok": True}))

    def log_message(self, *a): pass


if __name__ == "__main__":
    print("iris is running on http://localhost:8765  (close this window to stop)")
    updater.start_background()
    threading.Timer(1.0, lambda: webbrowser.open("http://localhost:8765")).start()
    ThreadingHTTPServer(("127.0.0.1", 8765), H).serve_forever()
