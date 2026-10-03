from memory import KnowledgeBase


def test_save_and_search(tmp_path):
    kb = KnowledgeBase(str(tmp_path / "m.db"))
    kb.save("research", "SwiftData migration", "Use VersionedSchema and a MigrationPlan", source="developer.apple.com",
            version="iOS 18", confidence=0.6, run_id="r1")
    out = kb.search("swiftdata migration")
    assert "SwiftData migration" in out and "developer.apple.com" in out and "unverified" in out


def test_promote_only_this_runs_unverified(tmp_path):
    kb = KnowledgeBase(str(tmp_path / "m.db"))
    kb.save("research", "a topic", "x", run_id="r1")
    kb.save("research", "b topic", "y", run_id="r2")
    kb.save("failed_approach", "c topic", "z", status="failed", run_id="r1")
    kb.promote("r1", "tests passed")
    rows = {r[2]: r[9] for r in kb.items("", 10)}
    assert rows == {"a topic": "verified", "b topic": "unverified", "c topic": "failed"}


def test_verified_ranks_above_unverified(tmp_path):
    kb = KnowledgeBase(str(tmp_path / "m.db"))
    kb.save("research", "habit streak logic", "unverified idea", confidence=0.5, run_id="r1")
    kb.save("verified_solution", "habit streak logic", "tested idea", confidence=0.9, status="verified", run_id="r2")
    assert kb.items("habit streak", 2)[0][9] == "verified"


def test_empty_and_symbol_queries(tmp_path):
    kb = KnowledgeBase(str(tmp_path / "m.db"))
    assert kb.search("") == "(no relevant memory)"
    assert kb.search('"); DROP TABLE knowledge; --') == "(no relevant memory)"
