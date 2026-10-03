"""Safe auto-update for dependencies, the Claude model, and the app's own code.
Every step: verify (checksum + selftest) -> backup -> apply -> roll back automatically on failure.
Settings (.env):  AUTO_UPDATE=auto|check|off   UPDATE_URL=https://.../version.json   AUTO_MODEL_FAMILY=sonnet|haiku|opus"""
import base64, hashlib, importlib.util, io, json, os, re, shutil, subprocess, sys, tempfile, threading, time, zipfile
from pathlib import Path
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
from events import log, STATE, redact
import paths

ROOT = Path(__file__).parent
STATE_FILE, BACKUPS = paths.state_file(), ROOT / "backups"
DEFAULT_MODEL = "claude-sonnet-5-5"
KEEP = {".venv", "workspace", "backups", "__pycache__", "brain_memory.db", ".env", ".env.offline",
        ".brain_state.json", "dist", "update_pubkey.txt", "trusted_packages.txt"}   # user-owned: updates never touch these


def _state():
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}

def _save(**kw):
    s = _state(); s.update(kw)
    STATE_FILE.write_text(json.dumps(s, indent=1), encoding="utf-8")

def _note(msg):
    log(f"[update] {msg}")
    _save(messages=(_state().get("messages", []) + [redact(f"{time.strftime('%d %b %H:%M')}  {msg}")])[-12:])

def mode():
    return os.getenv("AUTO_UPDATE", "auto").lower()

def local_version():
    try:
        return (ROOT / "VERSION").read_text().strip()
    except Exception:
        return "0.0.0"

def current_model():
    return os.getenv("BRAIN_MODEL") or _state().get("model") or DEFAULT_MODEL

def _vt(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:4])

def _fetch(url, max_mb=100):
    p = urlparse(url)
    if p.scheme != "https" and not (p.scheme == "file" and os.getenv("ALLOW_FILE_UPDATES") == "1"):
        raise ValueError("update URL must be https")
    with urlopen(Request(url, headers={"User-Agent": "iris-updater"}), timeout=60) as r:
        data = r.read(max_mb * 1048576 + 1)
    if len(data) > max_mb * 1048576:
        raise ValueError("download too large")
    return data

ALLOWED_EXT = {".py", ".bat", ".command", ".txt", ".md", ".json", ".yml", ".yaml", ""}   # no .exe/.dll/.ps1/.sh/.js ...

def trusted():
    try:
        lines = (ROOT / "trusted_packages.txt").read_text(encoding="utf-8").splitlines()
    except Exception:
        return set()
    return {l.split("#")[0].strip().lower().replace("_", "-") for l in lines if l.split("#")[0].strip()}

def _names(req_text):
    out = set()
    for l in req_text.splitlines():
        l = l.split("#")[0].strip()
        if l:
            out.add(re.split(r"[<>=!~\[ ;]", l)[0].lower().replace("_", "-"))
    return out

def verify_manifest(raw, sig, pubkey_b64):
    """Ed25519 signature check. Raises on any problem."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    Ed25519PublicKey.from_public_bytes(base64.b64decode(pubkey_b64)).verify(sig, raw)

def _vet_zip(z):
    if sum(i.file_size for i in z.infolist()) > 300 * 1048576:
        raise ValueError("update unpacks too large")
    for i in z.infolist():
        if i.is_dir():
            continue
        if (i.external_attr >> 16) & 0o170000 == 0o120000:
            raise ValueError("symlink inside update")
        if Path(i.filename).suffix.lower() not in ALLOWED_EXT:
            raise ValueError(f"file type not allowed in update: {i.filename}")

def _copy_tree(src, dst):
    for item in src.iterdir():
        if item.name in KEEP:
            continue
        if item.is_dir():
            shutil.copytree(item, dst / item.name, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__"))
        else:
            shutil.copy2(item, dst / item.name)

def _selftest(path):
    r = subprocess.run([sys.executable, "selftest.py"], cwd=path, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=180)
    return r.returncode == 0 and "SELFTEST OK" in r.stdout, (r.stdout + r.stderr)[-600:]


# ---- 1. app code ----------------------------------------------------------------------------
def update_code(apply=True):
    url = os.getenv("UPDATE_URL")
    if not url:
        return "Code: UPDATE_URL not set (skipped)."
    raw = _fetch(url)
    pk_file = ROOT / "update_pubkey.txt"
    pk = pk_file.read_text().strip() if pk_file.exists() else ""
    if pk:
        verify_manifest(raw, base64.b64decode(_fetch(url + ".sig")), pk)      # authenticity: only YOUR key can sign
    elif os.getenv("ALLOW_UNSIGNED_UPDATES") != "1":
        raise ValueError("no signing key: run gen_keys.py and put the public key in update_pubkey.txt (code updates disabled)")
    m = json.loads(raw)
    new, cur = m["version"], local_version()
    if _vt(new) <= _vt(cur):
        _save(update_available=None)
        return f"Code: up to date (v{cur})."
    if not apply:
        _save(update_available=new)
        return f"Code: update v{new} available (you have v{cur})."
    data = _fetch(urljoin(url, m["zip"]))
    if hashlib.sha256(data).hexdigest() != m["sha256"].lower():
        raise ValueError("checksum mismatch, update rejected")
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        z = zipfile.ZipFile(io.BytesIO(data))
        for n in z.namelist():
            if not (tmp / n).resolve().is_relative_to(tmp.resolve()):
                raise ValueError("unsafe path in update zip")
        _vet_zip(z)
        z.extractall(tmp)
        new_root = min(tmp.rglob("main.py"), key=lambda p: len(p.parts)).parent
        extra = _names((new_root / "requirements.txt").read_text(encoding="utf-8")) - trusted() if (new_root / "requirements.txt").exists() else set()
        if extra:
            raise ValueError("update needs new dependencies not in trusted_packages.txt: " + ", ".join(sorted(extra)) + " (approve manually)")
        ok, out = _selftest(new_root)
        if not ok:
            raise ValueError("new version failed selftest, NOT installed: " + out[-200:])
        b = BACKUPS / f"v{cur}_{int(time.time())}"
        b.mkdir(parents=True)
        _copy_tree(ROOT, b)
        try:
            _copy_tree(new_root, ROOT)
            (ROOT / "VERSION").write_text(new)
        except Exception:
            _copy_tree(b, ROOT)
            raise
    _save(update_available=None, restart_needed=True)
    return f"Code: updated v{cur} -> v{new}. Restart to use it."

def rollback():
    if not BACKUPS.exists() or not any(BACKUPS.iterdir()):
        return "Rollback: no backup available."
    b = sorted(BACKUPS.iterdir())[-1]
    _copy_tree(b, ROOT)
    shutil.rmtree(b)
    _save(restart_needed=True)
    msg = f"Rolled back to v{local_version()}. Restart to use it."
    _note(msg)
    return msg


# ---- 2. python packages ---------------------------------------------------------------------
def _pins(names):
    out = subprocess.run([sys.executable, "-m", "pip", "freeze"], capture_output=True, text=True).stdout
    return {l.split("==")[0].lower().replace("_", "-"): l for l in out.splitlines()
            if "==" in l and l.split("==")[0].lower().replace("_", "-") in names}

def update_dependencies():
    lines = [l.split("#")[0].strip() for l in (ROOT / "requirements.txt").read_text().splitlines()]
    pkgs = [l for l in lines if l and re.split(r"[<>=!~\[ ]", l)[0].lower().replace('_', '-') in trusted()]
    if "airllm" in trusted() and importlib.util.find_spec("airllm") and not any(p.startswith("airllm") for p in pkgs):
        pkgs.append("airllm")
    names = {re.split(r"[<>=!~\[ ]", p)[0].lower().replace("_", "-") for p in pkgs}
    before = _pins(names)
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--upgrade", "-q", *pkgs], capture_output=True, text=True)
    after = _pins(names)
    changed = [n for n in after if after[n] != before.get(n)]
    if r.returncode == 0 and not changed:
        return "Packages: already latest."
    ok = r.returncode == 0 and _selftest(ROOT)[0]
    if not ok:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", *before.values()], capture_output=True)
        return "Packages: upgrade failed tests, reverted to previous versions."
    _save(restart_needed=True)
    return "Packages: upgraded " + ", ".join(changed) + ". Restart to use them."


# ---- 3. Claude model ------------------------------------------------------------------------
def update_model(apply=True):
    if os.getenv("BRAIN_MODEL"):
        return "Model: fixed by BRAIN_MODEL (skipped)."
    if os.getenv("BRAIN_BACKEND", "claude").lower() != "claude" or not os.getenv("ANTHROPIC_API_KEY"):
        return "Model: skipped (needs Claude backend + API key)."
    import anthropic
    c, fam = anthropic.Anthropic(), os.getenv("AUTO_MODEL_FAMILY", "sonnet").lower()
    cands = [m for m in c.models.list(limit=100).data if m.id.startswith(f"claude-{fam}")]
    if not cands:
        return f"Model: no '{fam}' models found."
    best = max(cands, key=lambda m: m.created_at)
    if best.id == current_model():
        return f"Model: already newest {fam} ({best.id})."
    if not apply:
        return f"Model: newer {fam} available: {best.id}."
    c.messages.create(model=best.id, max_tokens=5, messages=[{"role": "user", "content": "hi"}])   # must work
    _save(model=best.id)
    return f"Model: switched {current_model()} -> {best.id} (tested, active now)."


# ---- runner ---------------------------------------------------------------------------------
def run_update(force=False):
    if STATE.get("status") == "running":
        return
    m, s = mode(), _state()
    if m == "off" and not force:
        return
    if not force and time.time() - s.get("last_check", 0) < 86400:
        return
    apply = force or m == "auto"
    steps = [lambda: update_model(apply), lambda: update_code(apply)] + ([update_dependencies] if apply else [])
    for step in steps:
        try:
            _note(step())
        except Exception as e:
            _note(f"step failed safely: {e}")
    _save(last_check=time.time())

def start_background():
    def loop():
        while True:
            try:
                run_update()
            except Exception as e:
                log(f"[update] {e}")
            time.sleep(3600)
    threading.Thread(target=loop, daemon=True).start()

def status():
    s = _state()
    return {"mode": mode(), "version": local_version(), "model": current_model(), "restart_needed": s.get("restart_needed", False),
            "update_available": s.get("update_available"), "messages": s.get("messages", [])[-6:],
            "update_url_set": bool(os.getenv("UPDATE_URL"))}
