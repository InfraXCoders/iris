"""Checks that this Mac can build and test apps with iris. Uses NO AI and NO tokens.

    python doctor.py            quick checks
    python doctor.py --smoke    also builds + tests a tiny built-in app on the iPhone simulator and on this Mac
"""
import config  # noqa: F401  (loads .env + Keychain)
import argparse, os, platform, shutil, subprocess, sys, tempfile
from pathlib import Path
import paths, runner, scaffold

OK, WARN, BAD = "OK  ", "WARN", "FAIL"


def sh(cmd, timeout=60):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, shell=False)
        return r.returncode == 0, (r.stdout + r.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, str(e)


def checks():
    rows = []
    add = lambda status, what, detail="": rows.append((status, what, detail))
    v = sys.version_info
    add(OK if v >= (3, 10) else BAD, "Python", f"{v.major}.{v.minor}.{v.micro}" + ("" if v >= (3, 10) else " (need 3.10+)"))
    mac = platform.system() == "Darwin"
    add(OK if mac else BAD, "macOS", platform.mac_ver()[0] if mac else f"this is {platform.system()}: iPhone/Mac builds need a Mac")
    if mac:
        ok, out = sh(["xcodebuild", "-version"])
        add(OK if ok else BAD, "Xcode", out.splitlines()[0] if ok else
            "not found: install Xcode from the App Store, open it once, then: sudo xcode-select -s /Applications/Xcode.app")
        ok, out = sh(["xcodegen", "--version"]) if shutil.which("xcodegen") else (False, "")
        add(OK if ok else BAD, "XcodeGen", out or "not found: brew install xcodegen")
        ok, out = sh(["xcrun", "simctl", "list", "devices", "available", "-j"], timeout=120)
        udid = runner.pick_simulator(runner._json_part(out)) if ok else None
        add(OK if udid else BAD, "iPhone simulator", udid or "none: Xcode > Settings > Components > install iOS")
    try:
        import anthropic
        add(OK, "anthropic package", anthropic.__version__)
    except ImportError:
        add(BAD, "anthropic package", "missing: run setup.command")
    backend = os.getenv("BRAIN_BACKEND", "claude").lower()
    if backend == "claude":
        key = os.getenv("ANTHROPIC_API_KEY", "")
        add(OK if key.startswith("sk-ant-") else BAD, "Anthropic API key",
            "found (value hidden)" if key else "missing: run setup.command to store it in the Keychain")
        if (paths.APP / ".env").exists() and "ANTHROPIC_API_KEY=" in (paths.APP / ".env").read_text(encoding="utf-8", errors="ignore"):
            add(WARN, "Key location", ".env holds the key in plain text: run setup.command to move it to the Keychain")
    else:
        add(OK, "AI backend", backend)
    budget = os.getenv("BRAIN_TOKEN_BUDGET", "")
    add(OK if budget else WARN, "Token budget", budget or "not set: add BRAIN_TOKEN_BUDGET=2000000 to .env to cap spending")
    add(OK, "Data folder", str(paths.home()))
    add(OK, "Projects folder", str(paths.workspace_root()))
    return rows


def smoke():
    """Scaffold the built-in starter app and run the real build + tests, exactly like an iris run does."""
    results = {}
    with tempfile.TemporaryDirectory(prefix="iris-smoke-") as t:
        apple = scaffold.create(t, "IrisSmoke", starter=True)
        for plat in ("ios", "macos"):
            print(f"\n--- building + testing IrisSmoke on {'iPhone simulator' if plat == 'ios' else 'this Mac'} (first time can take a few minutes)...", flush=True)
            status, out = runner.build_and_test(str(apple), plat)
            results[plat] = status
            print(f"    result: {status.upper()}")
            if status != "pass":
                print("    details:\n" + "\n".join("      " + l for l in out.splitlines()[:40]))
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke", action="store_true", help="also build and test a tiny app (no AI)")
    a = ap.parse_args()
    print("iris doctor\n")
    rows = checks()
    for s, what, detail in rows:
        print(f"  [{s}] {what:20s} {detail}")
    failed = [w for s, w, _ in rows if s == BAD]
    if a.smoke:
        res = smoke()
        print("\nSMOKE TEST: " + "  ".join(f"{p}={s.upper()}" for p, s in res.items()))
        failed += [p for p, s in res.items() if s != "pass"]
    print("\n" + ("All good." if not failed else "Fix these first: " + ", ".join(failed)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
