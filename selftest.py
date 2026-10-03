"""Sanity checks run BEFORE any update is accepted (no API calls, no internet, no Xcode needed)."""
import compileall, os, re, sys, tempfile
from pathlib import Path

root = Path(__file__).resolve().parent
_tmp = tempfile.TemporaryDirectory(prefix="iris-selftest-")
os.environ["IRIS_HOME"] = str(Path(_tmp.name) / "home")             # never touch the user's real data
os.environ["IRIS_WORKSPACE"] = str(Path(_tmp.name) / "workspace")
assert compileall.compile_dir(str(root), quiet=1, rx=re.compile(r"[\\/](\.venv|workspace|backups|dist)[\\/]")), "compile error"
sys.path.insert(0, str(root))
import agents, orchestrator, runner, research_tools, updater, doctor   # noqa: E402,F401
import scaffold, paths                                                  # noqa: E402
from memory import KnowledgeBase                                        # noqa: E402
from tools import run_tool, resolve_command                             # noqa: E402

d = _tmp.name
kb = KnowledgeBase(str(Path(d) / "t.db"))
kb.save("research", "swiftdata migration", "use VersionedSchema", run_id="x")
assert "swiftdata" in kb.search("swiftdata migration").lower(), "memory search broken"
kb.db.close()
run_tool("write_file", {"path": "a.txt", "content": "ok"}, d)
assert run_tool("read_file", {"path": "a.txt"}, d) == "ok", "file tools broken"
assert run_tool("write_file", {"path": "../x.txt", "content": "x"}, d).startswith("ERROR"), "workspace escape not blocked"
try:
    resolve_command("curl https://example.com", d)
    raise AssertionError("allowlist broken")
except ValueError:
    pass
apple = scaffold.create(d, "Self Test", starter=True)
yml = (apple / "project.yml").read_text(encoding="utf-8")
assert "SelfTest-iOS:" in yml and "SelfTest-macOS:" in yml and (apple / "Tests" / "SelfTestTests.swift").exists(), "scaffold broken"
assert str(paths.home()).startswith(d), "data folder override broken"
_tmp.cleanup()
print("SELFTEST OK")
