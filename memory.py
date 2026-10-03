"""Shared AI memory. Every item records SOURCE -> DATE -> VERSION -> EVIDENCE -> CONFIDENCE.
Raw research is stored as 'unverified' and only promoted to 'verified' after tests pass.
Search: SQLite FTS5 (BM25) re-ranked by confidence, verification status and recency."""
import sqlite3, datetime, re

KINDS = ("verified_solution", "successful_implementation", "failed_approach", "debugging_solution",
         "api_knowledge", "library", "framework", "android", "ios", "project", "skill", "research")
COLS = "id,kind,title,content,source,date,version,evidence,confidence,status"


class KnowledgeBase:
    def __init__(self, path=None):
        if path is None:
            import paths
            path = str(paths.memory_db())
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("""CREATE TABLE IF NOT EXISTS knowledge(
            id INTEGER PRIMARY KEY, kind TEXT, title TEXT, content TEXT, source TEXT, date TEXT,
            version TEXT, evidence TEXT, confidence REAL, status TEXT, run_id TEXT)""")
        try:
            self.db.executescript("""
            CREATE VIRTUAL TABLE IF NOT EXISTS kfts USING fts5(title, content, content='knowledge', content_rowid='id');
            CREATE TRIGGER IF NOT EXISTS k_ai AFTER INSERT ON knowledge BEGIN
              INSERT INTO kfts(rowid,title,content) VALUES(new.id,new.title,new.content); END;
            CREATE TRIGGER IF NOT EXISTS k_ad AFTER DELETE ON knowledge BEGIN
              INSERT INTO kfts(kfts,rowid,title,content) VALUES('delete',old.id,old.title,old.content); END;""")
            self.db.execute("INSERT INTO kfts(kfts) VALUES('rebuild')")
            self.fts = True
        except sqlite3.OperationalError:
            self.fts = False
        self.db.commit()

    def save(self, kind, title, content, source="agent", version="", evidence="",
             confidence=0.5, status="unverified", run_id=""):
        self.db.execute("INSERT INTO knowledge(kind,title,content,source,date,version,evidence,confidence,status,run_id)"
                        " VALUES(?,?,?,?,?,?,?,?,?,?)",
                        (kind, title, content, source, datetime.date.today().isoformat(),
                         version, evidence[:4000], confidence, status, run_id))
        self.db.commit()

    def promote(self, run_id, evidence):
        """Tests passed: unverified research from this run becomes verified."""
        self.db.execute("UPDATE knowledge SET status='verified', evidence=?, confidence=0.9 "
                        "WHERE run_id=? AND status='unverified'", (evidence[:4000], run_id))
        self.db.commit()

    def _rank(self, query, limit):
        words = list(dict.fromkeys(re.findall(r"\w{3,}", query.lower())))[:12]
        if not words:
            return []
        if self.fts:
            q = " OR ".join(f'"{w}"' for w in words)
            rows = self.db.execute(f"SELECT {', '.join('k.'+c for c in COLS.split(','))}, bm25(kfts) FROM kfts "
                                   "JOIN knowledge k ON k.id=kfts.rowid WHERE kfts MATCH ? ORDER BY bm25(kfts) LIMIT 60",
                                   (q,)).fetchall()
            base = [(r[:10], -r[10]) for r in rows]
        else:
            rows = self.db.execute(f"SELECT {COLS} FROM knowledge").fetchall()
            base = [(r, sum(w in (r[2] + r[3]).lower() for w in words)) for r in rows]
        today = datetime.date.today()
        def score(item):
            r, rel = item
            try:
                age = (today - datetime.date.fromisoformat(r[5])).days
            except ValueError:
                age = 0
            return rel * (0.5 + r[8]) * (1.5 if r[9] == "verified" else 1.0) / (1 + age / 365)
        return [r for r, rel in sorted(base, key=score, reverse=True) if rel > 0][:limit]

    def items(self, query="", limit=8):
        if not query.strip():
            return self.db.execute(f"SELECT {COLS} FROM knowledge ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return self._rank(query, limit)

    def search(self, query, limit=8):
        rows = self.items(query, limit)
        return "\n\n".join(
            f"[{r[1]}|{r[9]}] {r[2]}\n{r[3][:600]}\n(source: {r[4]} | {r[5]} | v{r[6]} | confidence {r[8]:.1f})"
            for r in rows) or "(no relevant memory)"
