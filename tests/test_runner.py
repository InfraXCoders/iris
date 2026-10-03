"""The Apple build pipeline, run against FAKE xcodegen/xcodebuild/xcrun programs that record their arguments.
This checks the exact commands iris runs. The real thing is checked on a Mac with:  python doctor.py --smoke"""
import json, os, stat, sys, textwrap
import pytest
import runner, scaffold

SIMS = {"devices": {
    "com.apple.CoreSimulator.SimRuntime.iOS-17-5": [{"name": "iPhone 15", "udid": "OLD-UDID", "isAvailable": True}],
    "com.apple.CoreSimulator.SimRuntime.iOS-18-2": [
        {"name": "iPad Air", "udid": "IPAD", "isAvailable": True},
        {"name": "iPhone 16 Pro", "udid": "NEW-UDID", "isAvailable": True},
        {"name": "iPhone 16", "udid": "SECOND", "isAvailable": True}],
    "com.apple.CoreSimulator.SimRuntime.watchOS-11-0": [{"name": "Apple Watch", "udid": "W", "isAvailable": True}],
    "com.apple.CoreSimulator.SimRuntime.iOS-18-4": [{"name": "iPhone 16e", "udid": "BROKEN", "isAvailable": False}],
}}


def test_pick_simulator_prefers_newest_iphone():
    assert runner.pick_simulator(json.dumps(SIMS)) == "NEW-UDID"
    assert runner.pick_simulator("not json") is None
    assert runner.pick_simulator(json.dumps({"devices": {}})) is None


def test_pick_scheme():
    lst = json.dumps({"project": {"name": "HabitTracker", "schemes": ["HabitTracker-iOS", "HabitTracker-macOS"]}})
    assert runner.pick_scheme(lst, "ios") == "HabitTracker-iOS"
    assert runner.pick_scheme(lst, "macos") == "HabitTracker-macOS"
    assert runner.pick_scheme("garbage", "ios") is None


def test_summarize_puts_errors_first():
    log = "noise\n" * 5000 + "Foo.swift:3:5: error: cannot find 'Habit' in scope\n" + "noise\n" * 5000 + "** TEST FAILED **\n"
    s = runner.summarize(log)
    assert s.startswith("KEY LINES:") and "cannot find 'Habit'" in s and "** TEST FAILED **" in s and len(s) <= runner.TAIL


def test_not_a_mac_is_skipped_never_passed(tmp_path, monkeypatch):
    monkeypatch.setattr(runner.platform, "system", lambda: "Linux")
    for plat in ("ios", "macos"):
        assert runner.build_and_test(str(tmp_path), plat)[0] == "skipped"


FAKE = r'''#!{py}
import json, os, sys
from pathlib import Path
log = Path(os.environ["FAKE_LOG"]) if "FAKE_LOG" in os.environ else Path(__file__).with_name("calls.log")
with log.open("a") as f:
    f.write(json.dumps([Path(sys.argv[0]).name] + sys.argv[1:]) + "\n")
tool, args = Path(sys.argv[0]).name, sys.argv[1:]
mode = Path(__file__).with_name("mode").read_text().strip()
if tool == "xcodegen":
    Path("HabitTracker.xcodeproj").mkdir(exist_ok=True); print("Created project"); sys.exit(0)
if tool == "xcrun":
    print({sims}); sys.exit(0)
if tool == "xcodebuild" and "-list" in args:
    print("warning: noise before json"); print(json.dumps({{"project": {{"schemes": ["HabitTracker-iOS", "HabitTracker-macOS"]}}}})); sys.exit(0)
if tool == "xcodebuild" and args[0] == "test":
    if mode == "pass":
        print("Test Suite 'All tests' passed\n** TEST SUCCEEDED **"); sys.exit(0)
    if mode == "silent":
        print("Build succeeded but nothing ran"); sys.exit(0)
    print("ContentView.swift:9:13: error: cannot find 'Habit' in scope\n** TEST FAILED **"); sys.exit(65)
sys.exit(2)
'''


@pytest.fixture
def fake_mac(tmp_path, monkeypatch):
    bin_ = tmp_path / "bin"; bin_.mkdir()
    for tool in ("xcodegen", "xcodebuild", "xcrun"):
        p = bin_ / tool
        p.write_text(FAKE.format(py=sys.executable, sims=repr(json.dumps(SIMS))))
        p.chmod(p.stat().st_mode | stat.S_IEXEC)
    (bin_ / "mode").write_text("pass")
    monkeypatch.setenv("PATH", f"{bin_}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setattr(runner.platform, "system", lambda: "Darwin")
    for k in ("IRIS_IOS_SCHEME", "IRIS_MACOS_SCHEME", "IOS_SCHEME", "IRIS_IOS_DESTINATION", "IOS_DESTINATION"):
        monkeypatch.delenv(k, raising=False)
    apple = scaffold.create(tmp_path / "proj", "Habit Tracker")
    def calls():
        f = bin_ / "calls.log"
        return [json.loads(l) for l in f.read_text().splitlines()] if f.exists() else []
    def mode(m):
        (bin_ / "mode").write_text(m)
    return apple, calls, mode


def test_ios_pipeline_runs_the_right_commands(fake_mac):
    apple, calls, _ = fake_mac
    status, out = runner.build_and_test(str(apple), "ios")
    assert status == "pass", out
    c = calls()
    assert c[0] == ["xcodegen", "generate", "--quiet"]
    assert c[1] == ["xcodebuild", "-list", "-json", "-project", "HabitTracker.xcodeproj"]
    assert c[2][:3] == ["xcrun", "simctl", "list"]
    assert c[3] == ["xcodebuild", "test", "-project", "HabitTracker.xcodeproj", "-scheme", "HabitTracker-iOS",
                    "-destination", "platform=iOS Simulator,id=NEW-UDID", "-derivedDataPath", ".build/DerivedData",
                    "-skipPackagePluginValidation", "CODE_SIGNING_ALLOWED=NO"]


def test_macos_pipeline(fake_mac):
    apple, calls, _ = fake_mac
    assert runner.build_and_test(str(apple), "macos")[0] == "pass"
    test_cmd = calls()[-1]
    assert test_cmd[test_cmd.index("-scheme") + 1] == "HabitTracker-macOS"
    assert test_cmd[test_cmd.index("-destination") + 1] == "platform=macOS"
    assert not any(c[0] == "xcrun" for c in calls())                 # no simulator needed for the Mac


def test_failed_tests_report_fail_with_error_first(fake_mac):
    apple, _, mode = fake_mac
    mode("fail")
    status, out = runner.build_and_test(str(apple), "ios")
    assert status == "fail" and out.startswith("KEY LINES:") and "cannot find 'Habit'" in out


def test_exit_zero_without_tests_is_not_a_pass(fake_mac):
    apple, _, mode = fake_mac
    mode("silent")
    assert runner.build_and_test(str(apple), "macos")[0] == "fail"


def test_missing_xcodegen_is_skipped(fake_mac, tmp_path):
    apple, _, _ = fake_mac
    (tmp_path / "bin" / "xcodegen").unlink()
    status, out = runner.build_and_test(str(apple), "ios")
    assert status == "skipped" and "brew install xcodegen" in out


def test_destination_override(fake_mac, monkeypatch):
    apple, calls, _ = fake_mac
    monkeypatch.setenv("IRIS_IOS_DESTINATION", "platform=iOS Simulator,name=iPhone 15")
    runner.build_and_test(str(apple), "ios")
    assert calls()[-1][calls()[-1].index("-destination") + 1] == "platform=iOS Simulator,name=iPhone 15"


def test_build_env_has_no_secrets(fake_mac, monkeypatch, tmp_path):
    """The build tools themselves receive the scrubbed environment."""
    apple, _, _ = fake_mac
    spy = tmp_path / "bin" / "xcodegen"
    spy.write_text(spy.read_text().replace('tool, args =', 'Path("env.json").write_text(json.dumps(dict(os.environ)))\ntool, args ='))
    runner.build_and_test(str(apple), "ios")
    env = json.loads((apple / "env.json").read_text())
    assert "ANTHROPIC_API_KEY" not in env and "PATH" in env
