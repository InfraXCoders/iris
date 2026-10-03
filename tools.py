"""File + shell tools given to the agents. Confined to the workspace.
Commands are ALLOWLISTED (not blocklisted), run without a shell, and never see your secrets."""
import os, re, shlex, shutil, subprocess, sys
from pathlib import Path

MAX_WRITE = 2_000_000
ENV_KEEP = {"PATH", "PATHEXT", "SYSTEMROOT", "COMSPEC", "TEMP", "TMP", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "GRADLE_OPTS", "LANG", "LC_ALL",
            "DEVELOPER_DIR", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMDATA", "HOMEDRIVE", "HOMEPATH", "USERNAME",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS", "TMPDIR", "USER", "LOGNAME"}
BAD_ARG = re.compile(r"[;&|<>`$%^\r\n]")
PATH_ESC = re.compile(r"(^|[\\/])\.\.([\\/]|$)|^[\\/]|^[A-Za-z]:[\\/]|^~")
GIT_OK = {"status", "diff", "add", "commit", "log", "init", "rev-parse"}
PY_MODS = {"pytest", "unittest", "py_compile"}
XCODEGEN_OK = {"generate"}
ALLOWED_HELP = ("Allowed: xcodegen generate, xcodebuild <args>, swift <build|test|package ...>, gradlew/gradle <args>, "
                "git (status|diff|add|commit|log|init|rev-parse), python -m pytest|unittest|py_compile. "
                "No pipes, redirects, '&&', absolute paths or '..'.")

TOOL_SCHEMAS = [
    {"name": "write_file", "description": "Create or overwrite a file in the project workspace (max 2MB).",
     "input_schema": {"type": "object", "properties": {
         "path": {"type": "string", "description": "Relative path"},
         "content": {"type": "string"}}, "required": ["path", "content"]}},
    {"name": "read_file", "description": "Read a file from the workspace.",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
    {"name": "list_files", "description": "List all files in the workspace.",
     "input_schema": {"type": "object", "properties": {}}},
    {"name": "run_command", "description": "Run ONE allowlisted build/test command in the workspace. " + ALLOWED_HELP,
     "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
]


def safe_env():
    keep = {k.upper() for k in ENV_KEEP}
    return {k: v for k, v in os.environ.items() if k.upper() in keep}      # API keys/tokens are NOT passed on


def _safe(workdir, rel):
    if str(rel).startswith("~"):
        raise ValueError("paths must be relative to the workspace ('~' is not allowed)")
    root = Path(workdir).resolve()
    p = (root / rel).resolve()
    if root not in p.parents and p != root:
        raise ValueError("path escapes workspace")
    if ".git" in p.relative_to(root).parts:
        raise ValueError("writing inside .git is not allowed")
    return p


def resolve_command(cmd, workdir):
    nt = os.name == "nt"
    toks = shlex.split(cmd, posix=not nt)
    if nt:
        toks = [t.strip("\"'") for t in toks]
    if not toks:
        raise ValueError("empty command")
    name = Path(toks[0].replace("\\", "/")).name.lower()
    for ext in (".bat", ".cmd", ".exe"):
        if name.endswith(ext):
            name = name[:-len(ext)]
    args = toks[1:]
    for a in args:
        if BAD_ARG.search(a):
            raise ValueError(f"blocked character in argument {a!r}")
        if PATH_ESC.search(a.split("=", 1)[-1]):
            raise ValueError(f"path outside workspace not allowed: {a!r}")
    if name == "gradlew":
        exe = Path(workdir) / ("gradlew.bat" if nt else "gradlew")
        if not exe.exists():
            raise ValueError("gradlew not found in workspace")
        return [str(exe), *args]
    if name == "xcodegen":
        if not args or args[0] not in XCODEGEN_OK:
            raise ValueError("only 'xcodegen generate' is allowed")
        exe = shutil.which("xcodegen")
        if not exe:
            raise ValueError("xcodegen is not installed (brew install xcodegen)")
        return [exe, *args]
    if name in ("gradle", "xcodebuild", "swift"):
        exe = shutil.which(name)
        if not exe:
            raise ValueError(f"{name} is not installed")
        return [exe, *args]
    if name == "git" and args and args[0] in GIT_OK:
        return [shutil.which("git") or "git", *args]
    if name in ("python", "python3", "py") and len(args) > 1 and args[0] == "-m" and args[1] in PY_MODS:
        return [sys.executable, *args]
    raise ValueError("command not allowed. " + ALLOWED_HELP)


def run_tool(name, args, workdir):
    try:
        if name == "write_file":
            if len(args["content"]) > MAX_WRITE:
                return "ERROR: file too large"
            p = _safe(workdir, args["path"])
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(args["content"], encoding="utf-8")
            return f"wrote {args['path']} ({len(args['content'])} chars)"
        if name == "read_file":
            return _safe(workdir, args["path"]).read_text(encoding="utf-8", errors="replace")[:20000]
        if name == "list_files":
            root = Path(workdir)
            files = [str(f.relative_to(root)) for f in root.rglob("*")
                     if f.is_file() and ".git" not in f.parts and "build" not in f.parts]
            return "\n".join(sorted(files)[:500]) or "(empty)"
        if name == "run_command":
            argv = resolve_command(args["command"], workdir)
            r = subprocess.run(argv, cwd=workdir, capture_output=True, text=True, encoding="utf-8", errors="replace",
                               timeout=600, env=safe_env(), shell=False)
            return (r.stdout + r.stderr)[-8000:] + f"\n[exit {r.returncode}]"
        return f"ERROR: unknown tool {name}"
    except Exception as e:
        return f"ERROR: {e}"
