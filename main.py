"""iris command line:  python main.py "A habit tracker with streaks" [--platforms ios macos]"""
import config  # noqa: F401
import argparse, platform
from orchestrator import Orchestrator, PLATFORMS

default = ["ios", "macos"] if platform.system() == "Darwin" else ["android"]
ap = argparse.ArgumentParser(description="iris: autonomous app development for iPhone and Mac")
ap.add_argument("task", help='e.g. "A habit tracker with streaks and reminders"')
ap.add_argument("--platforms", nargs="+", default=default, choices=PLATFORMS)
ap.add_argument("--workspace", default=None, help="project folder (default: a new folder in ~/iris-workspace)")
ap.add_argument("--max-iters", type=int, default=4, help="max build/test attempts per platform (1-10)")
a = ap.parse_args()
Orchestrator(a.workspace, a.platforms, a.max_iters).run(a.task)
