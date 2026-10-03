"""Tiny event log shared by the CLI and the web UI. Secrets are redacted before anything is printed or stored."""
import os, re, time, threading
LOGS, STATE, LOCK = [], {"status": "idle", "results": None, "task": ""}, threading.Lock()
_PAT = re.compile(r"(sk-or-v1-[A-Za-z0-9]{10,}|sk-ant-[A-Za-z0-9_\-]{10,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_\-]{30,})")

def redact(s):
    s = str(s)
    for k in ("ANTHROPIC_API_KEY", "GITHUB_TOKEN", "YOUTUBE_API_KEY", "UPDATE_SIGNING_KEY", "OPENROUTER_API_KEY", "OMNIROUTE_API_KEY"):
        v = os.getenv(k)
        if v and len(v) > 8:
            s = s.replace(v, "[REDACTED]")
    return _PAT.sub("[REDACTED]", s)

def log(msg=""):
    msg = redact(msg)
    print(msg, flush=True)
    with LOCK:
        LOGS.append(f"{time.strftime('%H:%M:%S')}  {msg}")
        del LOGS[:-1500]
