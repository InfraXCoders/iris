import subprocess
import paths, keychain, tools


def test_data_and_workspace_overrides(tmp_path, monkeypatch):
    monkeypatch.setenv("IRIS_HOME", str(tmp_path / "h")); monkeypatch.setenv("IRIS_WORKSPACE", str(tmp_path / "w"))
    assert paths.memory_db() == tmp_path / "h" / "memory.db"
    p = paths.new_project_dir("A Habit tracker!! with streaks & reminders")
    assert p.parent == tmp_path / "w" and p.name.startswith("a-habit-tracker-with-")


def test_legacy_memory_is_copied_not_moved(tmp_path, monkeypatch):
    app = tmp_path / "app"; app.mkdir(); (app / "brain_memory.db").write_bytes(b"old")
    monkeypatch.setattr(paths, "APP", app); monkeypatch.setenv("IRIS_HOME", str(tmp_path / "h"))
    assert paths.memory_db().read_bytes() == b"old" and (app / "brain_memory.db").exists()


def test_keychain_only_on_mac(monkeypatch):
    monkeypatch.setattr(keychain.platform, "system", lambda: "Linux")
    assert keychain.read("ANTHROPIC_API_KEY") is None


def test_keychain_read_and_env_wins(monkeypatch):
    calls = []
    def fake_run(cmd, **kw):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0, stdout="sk-ant-from-keychain\n", stderr="")
    monkeypatch.setattr(keychain.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(keychain.subprocess, "run", fake_run)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-from-env")
    for k in keychain.KEYS[1:]:
        monkeypatch.delenv(k, raising=False)
    found = keychain.load_into_env()
    import os
    assert os.environ["ANTHROPIC_API_KEY"] == "sk-ant-from-env"                  # explicit setting wins
    assert "ANTHROPIC_API_KEY" not in found
    assert calls[0] == ["/usr/bin/security", "find-generic-password", "-s", "iris", "-a", "OPENROUTER_API_KEY", "-w"]


def test_keychain_missing_item(monkeypatch):
    monkeypatch.setattr(keychain.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(keychain.subprocess, "run",
                        lambda cmd, **kw: subprocess.CompletedProcess(cmd, 44, stdout="", stderr="not found"))
    assert keychain.read("GITHUB_TOKEN") is None


def test_xcodegen_only_generate(tmp_path):
    import pytest
    for bad in ("xcodegen dump", "xcodegen --spec /etc/x.yml", "xcodegen"):
        with pytest.raises(ValueError):
            tools.resolve_command(bad, str(tmp_path))
