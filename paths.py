"""Where iris keeps things. App code, user data and generated projects live in SEPARATE places,
so code that iris generates and builds cannot casually read iris's own files (memory, settings).

  app code      : this folder
  data          : ~/Library/Application Support/iris  (memory.db, state.json)   override: IRIS_HOME
  workspace     : ~/iris-workspace/<project>          (generated apps)         override: IRIS_WORKSPACE
"""
import os, platform, re, shutil, time
from pathlib import Path

APP = Path(__file__).resolve().parent


def _default_home():
    if platform.system() == "Darwin":
        return Path.home() / "Library" / "Application Support" / "iris"
    return Path.home() / ".iris"


def home():
    p = Path(os.getenv("IRIS_HOME") or _default_home()).expanduser()
    p.mkdir(parents=True, exist_ok=True)
    return p


def workspace_root():
    p = Path(os.getenv("IRIS_WORKSPACE") or (Path.home() / "iris-workspace")).expanduser()
    p.mkdir(parents=True, exist_ok=True)
    return p


def _migrate(old, new):
    """Older versions kept data inside the app folder: copy it over once, never delete the original."""
    if old.exists() and not new.exists():
        shutil.copy2(old, new)


def memory_db():
    p = home() / "memory.db"
    _migrate(APP / "brain_memory.db", p)
    return p


def state_file():
    p = home() / "state.json"
    _migrate(APP / ".brain_state.json", p)
    return p


def new_project_dir(task):
    """~/iris-workspace/habit-tracker-1730000000"""
    slug = "-".join(re.findall(r"[a-z0-9]+", task.lower())[:4]) or "app"
    p = workspace_root() / f"{slug[:40]}-{int(time.time())}"
    p.mkdir(parents=True)
    return p
