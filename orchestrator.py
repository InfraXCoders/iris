"""Master AI Orchestrator: task -> subtasks -> parallel agents -> combine -> controlled dev loop.

RESEARCH -> PLAN -> CODE -> BUILD -> TEST -> (FAIL -> ERROR ANALYSIS -> RESEARCH -> FIX) -> RETEST
-> PASS -> SAVE KNOWLEDGE -> REUSABLE SKILL
iPhone and Mac share ONE SwiftUI project (apple/), scaffolded without AI; Android (optional) is separate.
"""
import json, re, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from agents import (research_agent, architect_agent, coding_agent, testing_agent,
                    debug_agent, orchestrator_llm, USAGE)
from memory import KnowledgeBase
from runner import build_and_test, APPLE
from events import log, STATE
import paths, scaffold

PLATFORMS = ("ios", "macos", "android")
LABEL = {"ios": "iPhone (iOS)", "macos": "Mac (macOS)", "android": "Android"}


def _json(text):
    try:
        m = re.search(r"\{.*\}", text, re.S)
        return json.loads(m.group(0)) if m else {}
    except Exception:
        return {}      # weak/local models sometimes break JSON: fall back to defaults


def project_dir_for(plat):
    return "apple" if plat in APPLE else plat


class Orchestrator:
    def __init__(self, workspace=None, platforms=("ios", "macos"), max_iters=4, memory_path=None):
        bad = [p for p in platforms if p not in PLATFORMS]
        if bad or not platforms:
            raise ValueError(f"unknown platform(s) {bad}; allowed: {PLATFORMS}")   # also stops '../x' folder names
        self.platforms = list(dict.fromkeys(platforms))
        self.max_iters = max(1, min(int(max_iters), 10))
        self.kb = KnowledgeBase(str(memory_path or paths.memory_db()))
        self.run_id = str(int(time.time()))
        self._workspace = Path(workspace) if workspace else None
        self.ws = None

    def run(self, task):
        STATE.update(status="running", results=None, task=task)
        try:
            return self._run(task)
        except Exception as e:
            log(f"STOPPED: {e}")
        finally:
            STATE["status"] = "done"

    def _run(self, task):
        log(f"\n== TASK: {task}")
        self.ws = self._workspace or paths.new_project_dir(task)
        self.ws.mkdir(parents=True, exist_ok=True)
        STATE["workspace"] = str(self.ws)
        log(f"== Project folder: {self.ws}")
        targets = ", ".join(LABEL[p] for p in self.platforms)

        # 1. Understand + break into subtasks
        plan = _json(orchestrator_llm().run(
            f"Task: {task}\nPlatforms: {targets}\nReturn JSON: "
            '{"app_name": "PascalCase name, letters only", "summary": str, '
            '"research_questions": [str, ...max 3], "features": [str, ...]}'))
        questions = [str(q) for q in (plan.get("research_questions") or [task])][:3]
        name = scaffold.app_name(plan.get("app_name") or task)
        memory = self.kb.search(task)
        log(f"== App name: {name}")
        log(f"== Memory recall:\n{memory[:500]}\n")

        # 2. Independent agents in parallel: research each question
        log("== Research (parallel)")
        with ThreadPoolExecutor(max_workers=3) as ex:
            notes = list(ex.map(lambda q: research_agent().run(q, f"Known memory:\n{memory}"), questions))
        for q, n in zip(questions, notes):
            self.kb.save("research", q, n, source="web research", confidence=0.5,
                         status="unverified", run_id=self.run_id)
        research = "\n\n".join(notes)

        # 3. Architecture
        log("== Architecture")
        arch = architect_agent().run(f"{task}\nApp name: {name}\nFeatures: {plan.get('features')}\nPlatforms: {targets}",
                                     f"Research:\n{research}\n\nMemory:\n{memory}")
        (self.ws / "ARCHITECTURE.md").write_text(arch, encoding="utf-8")

        # 4. Scaffold (no AI) + code each project folder in parallel
        dirs = list(dict.fromkeys(project_dir_for(p) for p in self.platforms))
        if "apple" in dirs:
            scaffold.create(self.ws, name, starter=True)
            log(f"== Scaffolded apple/ ({name}-iOS and {name}-macOS schemes)")
        log("== Coding (parallel)")

        def code(folder):
            d = self.ws / folder
            d.mkdir(exist_ok=True)
            plats = [LABEL[p] for p in self.platforms if project_dir_for(p) == folder]
            what = f"the {name} app for {', '.join(plats)}"
            coding_agent(str(d), folder).run(f"Implement {what}: {task}", f"Architecture:\n{arch}")
            testing_agent(str(d), folder).run(f"Write tests for {what}: {task}", f"Architecture:\n{arch}")
        with ThreadPoolExecutor() as ex:
            list(ex.map(code, dirs))

        # 5. Build -> test -> (fail -> error analysis -> research -> fix) loop, one platform at a time
        results = {plat: self._loop(plat, task, arch) for plat in self.platforms}

        # 6. Save knowledge / reusable skill only when every platform really passed
        if all(r["status"] == "pass" for r in results.values()):
            evidence = "\n".join(f"{p}: {r['log'][-500:]}" for p, r in results.items())
            self.kb.promote(self.run_id, evidence)
            self._save_skill(task, arch, evidence)
        log("\n== RESULT")
        log(f"  project: {self.ws}")
        log(f"  tokens used this session: {USAGE['tokens']:,}")
        for p, r in results.items():
            log(f"  {LABEL[p]}: {r['status'].upper()} after {r['iters']} build(s)")
        STATE["results"] = {p: {k: r[k] for k in ('status', 'iters')} for p, r in results.items()}
        return results

    def _loop(self, plat, task, arch):
        """BUILD -> TEST -> (FAIL -> ERROR ANALYSIS -> RESEARCH -> FIX) -> RETEST, at most max_iters builds."""
        folder = project_dir_for(plat)
        d = str(self.ws / folder)
        out = ""
        for i in range(1, self.max_iters + 1):
            status, out = build_and_test(d, plat)
            log(f"== {LABEL[plat]} build/test #{i}: {status}")
            if status != "fail":
                if status == "skipped":
                    log(f"   reason: {out[:300]}")
                return {"status": status, "log": out, "iters": i}
            if i == self.max_iters:
                break                                   # no point fixing after the last allowed build
            fix = debug_agent(d, folder).run(f"Build/test failed for {LABEL[plat]}. Find the root cause and fix it.",
                                             f"Task: {task}\nArchitecture:\n{arch}\n\nLOG:\n{out}\n\n"
                                             f"Past knowledge:\n{self.kb.search(out[-500:])}")
            # A failure is a fact about what did NOT work: stored as 'failed', never as 'verified'.
            self.kb.save("failed_approach", f"{plat} failure #{i}", f"Error:\n{out[-1500:]}\n\nAnalysis/fix:\n{fix}",
                         source="debug agent", evidence=out[-800:], confidence=0.6,
                         status="failed", run_id=self.run_id)
        return {"status": "fail", "log": out, "iters": self.max_iters}

    def _save_skill(self, task, arch, evidence):
        skill = orchestrator_llm().run(
            "Distil a reusable skill (markdown: when to use, steps, pitfalls) from this successful build.\n"
            f"Task: {task}\nArchitecture:\n{arch[:3000]}", "(Ignore the JSON-only rule for this reply; return markdown.)")
        sk = self.ws / "skills"; sk.mkdir(exist_ok=True)
        (sk / f"{self.run_id}.md").write_text(skill, encoding="utf-8")
        self.kb.save("skill", task[:80], skill, source="successful run", evidence=evidence,
                     confidence=0.9, status="verified", run_id=self.run_id)
