"""The development loop with a mock AI and a mock build: no API calls, no toolchain needed."""
import json, pytest
import orchestrator


class Recorder:
    def __init__(self):
        self.calls = []

    def agent(self, name, reply="ok"):
        rec = self
        class A:
            def run(self, task, context="", **k):
                rec.calls.append((name, task))
                return reply
        return A()


@pytest.fixture
def wired(monkeypatch, tmp_path):
    rec = Recorder()
    plan = json.dumps({"app_name": "Habit Tracker", "summary": "s", "research_questions": ["q1", "q2"], "features": ["streaks"]})
    monkeypatch.setattr(orchestrator, "orchestrator_llm", lambda: rec.agent("orch", plan))
    monkeypatch.setattr(orchestrator, "research_agent", lambda: rec.agent("research", "finding (source: docs)"))
    monkeypatch.setattr(orchestrator, "architect_agent", lambda: rec.agent("architect", "# plan"))
    monkeypatch.setattr(orchestrator, "coding_agent", lambda d, f="apple": rec.agent("coding"))
    monkeypatch.setattr(orchestrator, "testing_agent", lambda d, f="apple": rec.agent("testing"))
    monkeypatch.setattr(orchestrator, "debug_agent", lambda d, f="apple": rec.agent("debug", "fixed import"))
    def make(results, platforms=("ios",)):
        seq = list(results)
        def fake_build(d, p):
            rec.calls.append(("build", f"{p}@{d}"))
            return seq.pop(0)
        monkeypatch.setattr(orchestrator, "build_and_test", fake_build)
        return orchestrator.Orchestrator(workspace=str(tmp_path / "ws"), platforms=list(platforms), max_iters=3,
                                          memory_path=str(tmp_path / "m.db"))
    return rec, make


def names(rec):
    return [c[0] for c in rec.calls]


def test_pass_first_time_promotes_and_saves_skill(wired):
    rec, make = wired
    o = make([("pass", "TEST SUCCEEDED")])
    r = o.run("habit tracker")
    assert r["ios"] == {"status": "pass", "log": "TEST SUCCEEDED", "iters": 1}
    assert names(rec).count("research") == 2 and "debug" not in names(rec)
    statuses = {row[9] for row in o.kb.items("", 20)}
    assert statuses == {"verified"}                          # research promoted + skill saved
    assert (o.ws / "skills").exists()


def test_fail_then_pass_runs_debug_once(wired):
    rec, make = wired
    o = make([("fail", "error: cannot find 'Habit' in scope"), ("pass", "TEST SUCCEEDED")])
    r = o.run("habit tracker")
    assert r["ios"]["status"] == "pass" and r["ios"]["iters"] == 2
    assert names(rec).count("debug") == 1
    kinds = {(row[1], row[9]) for row in o.kb.items("", 20)}
    assert ("failed_approach", "failed") in kinds              # never stored as 'verified'


def test_always_failing_stops_at_limit_and_promotes_nothing(wired):
    rec, make = wired
    o = make([("fail", "e1"), ("fail", "e2"), ("fail", "e3")])
    r = o.run("habit tracker")
    assert r["ios"] == {"status": "fail", "log": "e3", "iters": 3}
    assert names(rec).count("debug") == 2                      # no fix after the last allowed build
    assert "verified" not in {row[9] for row in o.kb.items("", 20)}


def test_skipped_is_never_reported_as_pass(wired):
    rec, make = wired
    o = make([("skipped", "iOS builds need macOS + Xcode.")])
    r = o.run("habit tracker")
    assert r["ios"]["status"] == "skipped"
    assert "verified" not in {row[9] for row in o.kb.items("", 20)}


def test_broken_plan_json_falls_back(wired, monkeypatch):
    rec, make = wired
    monkeypatch.setattr(orchestrator, "orchestrator_llm", lambda: rec.agent("orch", "not json at all"))
    o = make([("pass", "ok")])
    assert o.run("habit tracker")["ios"]["status"] == "pass"


@pytest.mark.parametrize("plats", [["../../escape"], ["windows"], []])
def test_rejects_unknown_platforms(tmp_path, plats):
    with pytest.raises(ValueError):
        orchestrator.Orchestrator(workspace=str(tmp_path / "ws"), platforms=plats, memory_path=str(tmp_path / "m.db"))
    assert not (tmp_path / "escape").exists()


def test_iphone_and_mac_share_one_scaffolded_project(wired):
    rec, make = wired
    o = make([("pass", "ok"), ("pass", "ok")], platforms=("ios", "macos"))
    r = o.run("habit tracker")
    assert set(r) == {"ios", "macos"} and all(v["status"] == "pass" for v in r.values())
    apple = o.ws / "apple"
    assert (apple / "project.yml").exists() and "HabitTracker-iOS:" in (apple / "project.yml").read_text()
    assert names(rec).count("coding") == 1                       # one shared codebase, coded once
    builds = [t for n, t in rec.calls if n == "build"]
    assert builds == [f"ios@{apple}", f"macos@{apple}"]


def test_default_workspace_is_outside_the_app_folder(wired, monkeypatch, tmp_path):
    rec, make = wired
    import paths
    monkeypatch.setenv("IRIS_WORKSPACE", str(tmp_path / "projects"))
    o = make([("pass", "ok")]); o._workspace = None
    o.run("Habit tracker with streaks")
    assert o.ws.parent == tmp_path / "projects" and o.ws.name.startswith("habit-tracker-with-streaks-")
    assert paths.APP not in o.ws.parents
