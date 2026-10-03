import pytest, yaml
import scaffold


@pytest.mark.parametrize("text,name", [("habit tracker", "HabitTracker"), ("My  App!!", "MyApp"),
                                       ("123 go", "IrisApp"), ("", "IrisApp"), ("x" * 50, "X" + "x" * 29),
                                       ("../../evil; rm -rf", "EvilRmRf")])
def test_app_name_is_safe(text, name):
    assert scaffold.app_name(text) == name


def test_project_yml_structure(tmp_path):
    apple = scaffold.create(tmp_path, "Habit Tracker")
    spec = yaml.safe_load((apple / "project.yml").read_text())
    t = spec["targets"]
    assert set(t) == {"HabitTracker-iOS", "HabitTracker-macOS", "HabitTracker-iOSTests", "HabitTracker-macOSTests"}
    assert t["HabitTracker-iOS"]["platform"] == "iOS" and t["HabitTracker-macOS"]["platform"] == "macOS"
    assert t["HabitTracker-iOSTests"]["dependencies"] == [{"target": "HabitTracker-iOS"}]
    assert t["HabitTracker-macOSTests"]["type"] == "bundle.unit-test"
    for tgt in ("HabitTracker-iOS", "HabitTracker-macOS"):
        assert t[tgt]["settings"]["base"]["PRODUCT_MODULE_NAME"] == "HabitTracker"      # one module name for @testable import
    assert spec["schemes"]["HabitTracker-iOS"]["test"]["targets"] == ["HabitTracker-iOSTests"]
    assert spec["schemes"]["HabitTracker-macOS"]["test"]["targets"] == ["HabitTracker-macOSTests"]
    assert spec["options"]["deploymentTarget"] == {"iOS": "17.0", "macOS": "14.0"}
    for src in ("Shared", "iOS", "macOS", "Tests"):
        assert (apple / src).is_dir()


def test_starter_app_is_consistent(tmp_path):
    apple = scaffold.create(tmp_path, "Habit Tracker")
    swift = {p.name: p.read_text() for p in apple.rglob("*.swift")}
    assert sum(s.count("@main") for s in swift.values()) == 1
    assert "@testable import HabitTracker" in swift["HabitTrackerTests.swift"]
    assert "struct Counter" in swift["ContentView.swift"] and "\\\\(" not in swift["ContentView.swift"]
    assert 'Text("Taps: \\(count.value)")' in swift["ContentView.swift"]     # Swift string interpolation


def test_never_overwrites_agent_work(tmp_path):
    apple = scaffold.create(tmp_path, "Habit Tracker")
    (apple / "Shared" / "ContentView.swift").write_text("// agent version")
    scaffold.create(tmp_path, "Habit Tracker")
    assert (apple / "Shared" / "ContentView.swift").read_text() == "// agent version"


@pytest.mark.parametrize("prefix", ["com.evil\nname: x", "../x", "Com.Example", "com"])
def test_rejects_bad_bundle_prefix(tmp_path, prefix):
    with pytest.raises(ValueError):
        scaffold.create(tmp_path, "App", bundle_prefix=prefix)
